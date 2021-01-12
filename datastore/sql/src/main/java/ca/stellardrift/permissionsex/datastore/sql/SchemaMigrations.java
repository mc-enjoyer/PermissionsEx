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
package ca.stellardrift.permissionsex.datastore.sql;

import ca.stellardrift.permissionsex.PermissionsEngine;
import ca.stellardrift.permissionsex.datastore.sql.dao.LegacyMigration;
import ca.stellardrift.permissionsex.datastore.sql.dao.SchemaMigration;
import ca.stellardrift.permissionsex.impl.util.PCollections;
import ca.stellardrift.permissionsex.legacy.LegacyConversions;
import ca.stellardrift.permissionsex.subject.SubjectRef;
import ca.stellardrift.permissionsex.context.ContextValue;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.pcollections.PVector;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.spongepowered.configurate.util.UnmodifiableCollections.immutableMapEntry;

/**
 * Schema migrations for the SQL database
 */
public class SchemaMigrations {
    public static final int VERSION_LATEST = 3;

    public static List<SchemaMigration> getMigrations() {
        List<SchemaMigration> migrations = new ArrayList<>();
        migrations.add(0, SchemaMigrations.initialToZero());
        migrations.add(1, SchemaMigrations.zeroToOne());
        migrations.add(2, SchemaMigrations.oneToTwo());
        migrations.add(VERSION_LATEST, SchemaMigrations.twoToThree());
        return migrations;
    }

    // Pre-2.x only needs to support MySQL because tbh nobody uses SQLite
    public static SchemaMigration twoToThree() {
        // The big one
        return dao -> {
            dao.legacy().renameTable(dao, "permissions", "permissions_old");
            dao.legacy().renameTable(dao, "permissions_entity", "permissions_entity_old");
            dao.legacy().renameTable(dao, "permissions_inheritance", "permissions_inheritance_old");
            dao.initializeTables();

            // Transfer world inheritance
            try (PreparedStatement stmt = dao.prepareStatement("SELECT id, child, parent FROM {}permissions_inheritance_old WHERE type=2 ORDER BY child, parent, id ASC")) {
                ResultSet rs = stmt.executeQuery();
                try (PreparedStatement insert = dao.prepareStatement(dao.getInsertContextInheritanceQuery())) {
                    insert.setString(1, "world");
                    insert.setString(3, "world");
                    while (rs.next()) {
                        insert.setString(2, rs.getString(2));
                        insert.setString(4, rs.getString(3));
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
            }

            Map<String, List<SqlSubjectRef<?>>> defaultSubjects = new HashMap<>();
            Map<String, List<Map.Entry<SqlSubjectRef<?>, Integer>>> tempRankLadders = new HashMap<>();

            try (PreparedStatement select = dao.prepareStatement("SELECT type, name FROM {}permissions_entity_old")) {
                ResultSet rs = select.executeQuery();
                while (rs.next()) {
                    final SqlSubjectRef<?> ref = dao.getOrCreateSubjectRef(LegacyMigration.Type.values()[rs.getInt(1)].name().toLowerCase(), rs.getString(2));
                    @Nullable SqlSegment currentSeg = null;
                    @Nullable String currentWorld = null;
                    Map<String, SqlSegment> worldSegments = new HashMap<>();
                    try (PreparedStatement selectPermissionsOptions = dao.prepareStatement("SELECT id, permission, world, value FROM {}permissions_old WHERE type=? AND name=? ORDER BY world, id DESC")) {
                        selectPermissionsOptions.setInt(1, rs.getInt(1));
                        selectPermissionsOptions.setString(2, rs.getString(2));

                        ResultSet perms = selectPermissionsOptions.executeQuery();
                        Map<String, Integer> newPerms = new HashMap<>();
                        Map<String, String> options = new HashMap<>();
                        @Nullable String rank = null;
                        @Nullable String rankLadder = null;
                        int defaultVal = 0;
                        while (perms.next()) {
                            @Nullable String worldChecked = perms.getString(3);
                            if (worldChecked != null && worldChecked.isEmpty()) {
                                worldChecked = null;
                            }
                            if (currentSeg == null || !Objects.equals(worldChecked, currentWorld)) {
                                if (currentSeg != null) {
                                    if (!options.isEmpty()) {
                                        dao.setOptions(currentSeg, options);
                                        options.clear();
                                    }
                                    if (!newPerms.isEmpty()) {
                                        dao.setPermissions(currentSeg, newPerms);
                                        newPerms.clear();
                                    }
                                    if (defaultVal != 0) {
                                        dao.setDefaultValue(currentSeg, defaultVal);
                                        defaultVal = 0;
                                    }
                                }
                                currentWorld = worldChecked;
                                currentSeg = SqlSegment.unallocated(currentWorld == null ? PCollections.set() : PCollections.set(new ContextValue<String>("world", currentWorld)));
                                dao.allocateSegment(ref, currentSeg);
                                worldSegments.put(currentWorld, currentSeg);
                            }
                            String key = perms.getString(2);
                            final String value = perms.getString(4);
                            if (value == null || value.isEmpty()) {
                                // permission
                                int val = key.startsWith("-") ? -1 : 1;
                                if (val == -1) {
                                    key = key.substring(1);
                                }
                                if (key.equals("*")) {
                                    defaultVal = val;
                                    continue;
                                }
                                key = LegacyConversions.convertLegacyPermission(key);
                                newPerms.put(key, val);
                            } else {
                                if (currentWorld == null) {
                                    boolean rankEq = key.equals("rank"), rankLadderEq = !rankEq && key.equals("rank-ladder");
                                    if (rankEq || rankLadderEq) {
                                        if (rankEq) {
                                            rank = value;
                                        } else { // then it's the rank ladder
                                            rankLadder = value;
                                        }
                                        if (rank != null && rankLadder != null) {
                                            List<Map.Entry<SqlSubjectRef<?>, Integer>> ladder = tempRankLadders.computeIfAbsent(rankLadder, ign -> new ArrayList<>());
                                            try {
                                                ladder.add(immutableMapEntry(ref, Integer.parseInt(rank)));
                                            } catch (IllegalArgumentException ignore) {
                                                // non-integer rank TODO maybe warn
                                            }
                                            rankLadder = null;
                                            rank = null;
                                        }
                                        continue;
                                    }
                                }
                                if (key.equals("default") && value.equalsIgnoreCase("true")) {
                                    defaultSubjects.computeIfAbsent(currentWorld, ign -> new ArrayList<>()).add(ref);
                                    continue;
                                }
                                options.put(key, value);
                            }
                        }

                        if (currentSeg != null) {
                            if (!options.isEmpty()) {
                                dao.setOptions(currentSeg, options);
                            }
                            if (!newPerms.isEmpty()) {
                                dao.setPermissions(currentSeg, newPerms);
                            }
                            if (defaultVal != 0) {
                                dao.setDefaultValue(currentSeg, defaultVal);
                            }
                            if (rank != null) {
                                List<Map.Entry<SqlSubjectRef<?>, Integer>> ladder = tempRankLadders.computeIfAbsent("default", ign -> new ArrayList<>());
                                try {
                                    ladder.add(immutableMapEntry(ref, Integer.parseInt(rank)));
                                } catch (IllegalArgumentException ex) {
                                    // non-integer rank TODO maybe warn
                                }

                            }
                        }
                    }

                    for (Map.Entry<String, List<Map.Entry<SqlSubjectRef<?>, Integer>>> ent : tempRankLadders.entrySet()) {
                        PVector<SubjectRef<?>> ladder = ent.getValue().stream()
                                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                                .map(Map.Entry::getKey)
                                .collect(PCollections.toPVector());
                        dao.setRankLadder(ent.getKey(), new SqlRankLadder(ent.getKey(), ladder));

                    }

                    if (!defaultSubjects.isEmpty()) {
                        final SqlSubjectRef<?> defaultSubj = dao.getOrCreateSubjectRef(dao.getDataStore().ctx().engine().fallbacks().type().name(), LegacyConversions.SUBJECTS_USER);
                        final List<SqlSegment> segments = new ArrayList<>(dao.getSegments(defaultSubj));
                        for (Map.Entry<String, List<SqlSubjectRef<?>>> ent : defaultSubjects.entrySet()) {
                            SqlSegment seg = null;
                            if (!segments.isEmpty()) {
                                for (SqlSegment segment : segments) {
                                    if (ent.getKey() == null && segment.contexts().isEmpty()) {
                                        seg = segment;
                                        break;
                                    } else if (segment.contexts().size() == 1) {
                                        ContextValue<?> ctx = segment.contexts().iterator().next();
                                        if (ctx.key().equals("world") && ctx.rawValue().equals(ent.getKey())) {
                                            seg = segment;
                                            break;
                                        }
                                    }
                                }
                            }
                            if (seg == null) {
                                seg = SqlSegment.unallocated(ent.getKey() == null ? PCollections.set() : PCollections.set(new ContextValue<String>("world", ent.getKey())));
                                dao.allocateSegment(defaultSubj, seg);
                                segments.add(seg);
                            }
                            dao.setParents(seg, ent.getValue());
                        }
                    }

                    try (PreparedStatement selectInheritance = dao.prepareStatement(dao.legacy().getSelectParentsQuery())) {
                        selectInheritance.setString(1, rs.getString(2));
                        selectInheritance.setInt(2, rs.getInt(1));

                        ResultSet inheritance = selectInheritance.executeQuery();
                        Deque<SqlSubjectRef<?>> newInheritance = new ArrayDeque<>();
                        while (inheritance.next()) {
                            if (currentSeg == null || !Objects.equals(inheritance.getString(3), currentWorld)) {
                                if (currentSeg != null && !newInheritance.isEmpty()) {
                                    dao.setParents(currentSeg, newInheritance);
                                    newInheritance.clear();
                                }
                                currentWorld = inheritance.getString(3);
                                currentSeg = worldSegments.get(currentWorld);
                                if (currentSeg == null) {
                                    currentSeg = SqlSegment.unallocated(currentWorld == null ? PCollections.set() : PCollections.set(new ContextValue<String>("world", currentWorld)));
                                    dao.allocateSegment(ref, currentSeg);
                                    worldSegments.put(currentWorld, currentSeg);
                                }
                            }
                            newInheritance.add(dao.getOrCreateSubjectRef(LegacyConversions.SUBJECTS_GROUP, inheritance.getString(2)));
                        }
                        if (currentSeg != null && !newInheritance.isEmpty()) {
                            dao.setParents(currentSeg, newInheritance);
                            newInheritance.clear();
                        }
                    }

                }
            }

            dao.deleteTable("permissions_old");
            dao.deleteTable("permissions_entity_old");
            dao.deleteTable("permissions_inheritance_old");
        };
    }

    public static SchemaMigration oneToTwo() {
        return dao -> {
            // Change encoding for all columns to utf8mb4
            // Change collation for all columns to utf8mb4_general_ci
            dao.legacy().prepareStatement(dao, "ALTER TABLE `{permissions}` DROP KEY `unique`, MODIFY COLUMN `permission` TEXT NOT NULL").execute();
        };
    }

    public static SchemaMigration zeroToOne() {
        return dao -> {
            PreparedStatement updateStmt = dao.prepareStatement(dao.legacy().getInsertOptionQuery());
            ResultSet res = dao.legacy().prepareStatement(dao, "SELECT `name`, `type` FROM `{permissions_entity}` WHERE `default`='1'").executeQuery();
            while (res.next()) {
                updateStmt.setString(1, res.getString(1));
                updateStmt.setInt(2, res.getInt(2));
                updateStmt.setString(3, "default");
                updateStmt.setString(4, "");
                updateStmt.setString(5, "true");
                updateStmt.addBatch();
            }
            updateStmt.executeBatch();

            // Update tables
            dao.prepareStatement("ALTER TABLE `{permissions_entity}` DROP COLUMN `default`").execute();
        };
    }

    public static SchemaMigration initialToZero() {
        return (LegacyMigration) dao -> {
            // TODO: Table modifications not supported in SQLite
            // Prefix/sufix -> options
            PreparedStatement updateStmt = dao.legacy().prepareStatement(dao, dao.legacy().getInsertOptionQuery());
            ResultSet res = dao.prepareStatement("SELECT `name`, `type`, `prefix`, `suffix` FROM `{permissions_entity}` WHERE LENGTH(`prefix`)>0 OR LENGTH(`suffix`)>0").executeQuery();
            while (res.next()) {
                String prefix = res.getString("prefix");
                if (!prefix.isEmpty() && !prefix.equals("null")) {
                    updateStmt.setString(1, res.getString(1));
                    updateStmt.setInt(2, res.getInt(2));
                    updateStmt.setString(3, "prefix");
                    updateStmt.setString(4, "");
                    updateStmt.setString(5, prefix);
                    updateStmt.addBatch();
                }
                String suffix = res.getString("suffix");
                if (!suffix.isEmpty() && !suffix.equals("null")) {
                    updateStmt.setString(1, res.getString(1));
                    updateStmt.setInt(2, res.getInt(2));
                    updateStmt.setString(3, "suffix");
                    updateStmt.setString(4, "");
                    updateStmt.setString(5, suffix);
                    updateStmt.addBatch();
                }
            }
            updateStmt.executeBatch();

            // Data type corrections

            // Update tables
            dao.prepareStatement("ALTER TABLE `{permissions_entity}` DROP KEY `name`").execute();
            dao.prepareStatement("ALTER TABLE `{permissions_entity}` DROP COLUMN `prefix`, DROP COLUMN `suffix`").execute();
            dao.prepareStatement("ALTER TABLE `{permissions_entity}` ADD CONSTRAINT UNIQUE KEY `name` (`name`, `type`)").execute();

            dao.prepareStatement("ALTER TABLE `{permissions}` DROP KEY `unique`").execute();
            dao.prepareStatement("ALTER TABLE `{permissions}` ADD CONSTRAINT UNIQUE `unique` (`name`,`permission`,`world`,`type`)").execute();
        };

    }
}
