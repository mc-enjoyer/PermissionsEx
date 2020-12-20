/*
 * PermissionsEx
 * Copyright (C) zml and PermissionsEx contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.stellardrift.permissionsex.subject;

import ca.stellardrift.permissionsex.PermissionsEx;
import ca.stellardrift.permissionsex.context.ContextDefinition;
import ca.stellardrift.permissionsex.context.ContextValue;
import ca.stellardrift.permissionsex.data.ToDataSubjectRefImpl;
import ca.stellardrift.permissionsex.util.CachingValue;
import ca.stellardrift.permissionsex.util.NodeTree;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.ImmutableSet;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * This is a holder that maintains the current subject data state
 */
public class CalculatedSubjectImpl<I> implements Consumer<ImmutableSubjectData>, CalculatedSubject {
    private final SubjectDataBaker baker;
    private final SubjectRef<I> identifier;
    private final SubjectTypeCollectionImpl<I> type;
    private @MonotonicNonNull ToDataSubjectRefImpl<I> ref;
    private @MonotonicNonNull ToDataSubjectRefImpl<I> transientRef;

    private final AsyncLoadingCache<Set<ContextValue<?>>, BakedSubjectData> data;
    private final Set<Consumer<CalculatedSubject>> updateListeners = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private @MonotonicNonNull CachingValue<Set<ContextValue<?>>> activeContexts;

    CalculatedSubjectImpl(
            final SubjectDataBaker baker,
            final SubjectRef<I> identifier,
            final SubjectTypeCollectionImpl<I> type) {
        this.baker = baker;
        this.identifier = identifier;
        this.type = type;
        this.data = Caffeine.newBuilder()
                .maximumSize(32)
                .expireAfterAccess(1, TimeUnit.MINUTES)
                .executor(type.getManager().asyncExecutor())
                .buildAsync((key, executor) -> this.baker.bake(CalculatedSubjectImpl.this, key));
    }

    void initialize(ToDataSubjectRefImpl<I> persistentRef, ToDataSubjectRefImpl<I> transientRef) {
        this.ref = persistentRef;
        this.transientRef = transientRef;
        this.activeContexts = CachingValue.timeBased(50L, () -> {
            Set<ContextValue<?>> acc = new HashSet<>();
            for (ContextDefinition<?> contextDefinition : getManager().getRegisteredContextTypes()) {
                handleAccumulateSingle(contextDefinition, acc);
            }
            return acc;
        });
    }

    @Override
    public SubjectRef<I> getIdentifier() {
        return identifier;
    }

    @Override
    public SubjectTypeCollectionImpl<I> getType() {
        return this.type;
    }

    PermissionsEx<?> getManager() {
        return this.type.getManager();
    }

    /**
     * Get the calculated data for a specific context set
     *
     * @param contexts The contexts to get data in. These will be processed for combinations
     * @return The baked subject data
     */
    private BakedSubjectData getData(Set<ContextValue<?>> contexts) {
        Objects.requireNonNull(contexts, "contexts");
        return data.synchronous().get(ImmutableSet.copyOf(contexts));
    }

    @Override
    public NodeTree getPermissions(Set<ContextValue<?>> contexts) {
        return getData(contexts).getPermissions();
    }

    @Override
    public Map<String, String> getOptions(Set<ContextValue<?>> contexts) {
        return getData(contexts).getOptions();
    }

    @Override
    public List<Map.Entry<String, String>> getParents(Set<ContextValue<?>> contexts) {
        List<Map.Entry<String, String>> parents = getData(contexts).getParents();
        getManager().getNotifier().onParentCheck(getIdentifier(), contexts, parents);
        return parents;
    }

    /**
     * Contexts that have been queried recently enough to still be cached
     * @return A set of context sets that are in the lookup cache
     */
    private Set<Set<ContextValue<?>>> getCachedContexts() {
        return data.synchronous().asMap().keySet();
    }

    @Override
    public Set<ContextValue<?>> getActiveContexts() {
        if (this.activeContexts == null) {
            throw new IllegalStateException("This subject has not yet been initialized! This is normally done before the future provided by PEX completes.");
        }
        return new HashSet<>(activeContexts.get());
    }

    @Override
    public CompletableFuture<Set<ContextValue<?>>> getUsedContextValues() {
        return getManager().getUsedContextTypes().thenApply(defs -> {
            Set<ContextValue<?>> acc = new HashSet<>();
            defs.forEach(def -> handleAccumulateSingle(def, acc));
            return acc;
        });
    }

    private <T> void handleAccumulateSingle(ContextDefinition<T> def, Set<ContextValue<?>> acc) {
        def.accumulateCurrentValues(this, val -> acc.add(def.createValue(val)));
    }

    @Override
    public int getPermission(Set<ContextValue<?>> contexts, String permission) {
        int ret = getPermissions(contexts).get(Objects.requireNonNull(permission, "permission"));
        getManager().getNotifier().onPermissionCheck(getIdentifier(), contexts, permission, ret);
        return ret;
    }

    @Override
    public boolean hasPermission(Set<ContextValue<?>> contexts, String permission) {
        final int perm = getPermission(contexts, permission);
        if (perm == 0) {
            return getType().getType().undefinedPermissionValue(this.identifier.identifier());
        } else {
            return perm > 0;
        }
    }

    @Override
    public Optional<String> getOption(Set<ContextValue<?>> contexts, String option) {
        final @Nullable String val = getOptions(contexts).get(Objects.requireNonNull(option, "option"));
        getManager().getNotifier().onOptionCheck(getIdentifier(), contexts, option, val);
        return Optional.ofNullable(val);
    }

    @Override
    public ConfigurationNode getOptionNode(Set<ContextValue<?>> contexts, String option) {
        String val = getOptions(contexts).get(Objects.requireNonNull(option, "option"));
        getManager().getNotifier().onOptionCheck(getIdentifier(), contexts, option, val);
        return BasicConfigurationNode.root().raw(val);
    }

    /**
     * Access this subject's persistent data
     *
     * @return A reference to the persistent data of this subject
     */
    @Override
    public ToDataSubjectRefImpl<I> data() {
        return this.ref;
    }

    @Override
    public ToDataSubjectRefImpl<I> transientData() {
        return this.transientRef;
    }

    @Override
    public @Nullable Object getAssociatedObject() {
        return this.type.getType().getAssociatedObject(this.identifier.identifier());
    }

    @Override
    public void registerListener(Consumer<CalculatedSubject> listener) {
        updateListeners.add(Objects.requireNonNull(listener));
    }

    @Override
    public void unregisterListener(Consumer<CalculatedSubject> listener) {
        updateListeners.remove(Objects.requireNonNull(listener));
    }

    @Override
    public void accept(ImmutableSubjectData newData) {
        data.synchronous().invalidateAll();
        getManager().loadedSubjectTypes().stream()
                .flatMap(type -> type.getActiveSubjects().stream())
                .map(it -> (CalculatedSubjectImpl<?>) it)
                .filter(subj -> {
                    for (Set<ContextValue<?>> ent : subj.getCachedContexts()) {
                        if (subj.getParents(ent).contains(this.identifier)) {
                            return true;
                        }
                    }
                    return false;
                })
                .forEach(subj -> subj.data.synchronous().invalidateAll());
        updateListeners.forEach(listener -> listener.accept(this));
    }

}
