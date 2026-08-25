package com.wuxianggujun.tinaide.plugin.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PluginSqlPolicyTest {

    @Test
    fun `allows normal plugin-local statements`() {
        assertThat(PluginSqlPolicy.isAllowedMutation("CREATE TABLE notes(id INTEGER PRIMARY KEY, body TEXT)"))
            .isTrue()
        assertThat(PluginSqlPolicy.isAllowedMutation("INSERT INTO notes(body) VALUES (?)")).isTrue()
        assertThat(PluginSqlPolicy.isAllowedQuery("SELECT id, body FROM notes WHERE id = ?")).isTrue()
        assertThat(PluginSqlPolicy.isAllowedQuery("WITH recent AS (SELECT * FROM notes) SELECT * FROM recent"))
            .isTrue()
        assertThat(PluginSqlPolicy.isAllowedQuery("SELECT 'PRAGMA is data, not a statement'"))
            .isTrue()
    }

    @Test
    fun `rejects database attachment and administrative statements`() {
        assertThat(PluginSqlPolicy.isAllowedMutation("ATTACH DATABASE '/data/data/host/private.db' AS host"))
            .isFalse()
        assertThat(PluginSqlPolicy.isAllowedQuery("PRAGMA database_list")).isFalse()
        assertThat(PluginSqlPolicy.isAllowedMutation("VACUUM INTO '/storage/emulated/0/leak.db'"))
            .isFalse()
        assertThat(PluginSqlPolicy.isAllowedQuery("SELECT load_extension('payload.so')")).isFalse()
        assertThat(PluginSqlPolicy.isAllowedQuery("SELECT \"load_extension\"('payload.so')")).isFalse()
        assertThat(PluginSqlPolicy.isAllowedQuery("WITH removed AS (SELECT 1) DELETE FROM notes RETURNING id"))
            .isFalse()
        assertThat(PluginSqlPolicy.isAllowedQuery("WITH RECURSIVE counter(x) AS (VALUES(1)) SELECT x FROM counter"))
            .isFalse()
        assertThat(PluginSqlPolicy.isAllowedQuery("SELECT randomblob(2147483647)")).isFalse()
        assertThat(PluginSqlPolicy.isAllowedQuery("SELECT replace /* expansion */ ('a', 'a', 'payload')"))
            .isFalse()
        assertThat(PluginSqlPolicy.isAllowedMutation("REPLACE INTO notes(id, body) VALUES (1, 'ok')"))
            .isTrue()
    }

    @Test
    fun `rejects statement of the wrong category and oversized SQL`() {
        assertThat(PluginSqlPolicy.isAllowedQuery("DELETE FROM notes")).isFalse()
        assertThat(PluginSqlPolicy.isAllowedMutation("SELECT * FROM notes")).isFalse()
        assertThat(PluginSqlPolicy.isAllowedQuery("SELECT " + "x".repeat(70 * 1024))).isFalse()
    }
}
