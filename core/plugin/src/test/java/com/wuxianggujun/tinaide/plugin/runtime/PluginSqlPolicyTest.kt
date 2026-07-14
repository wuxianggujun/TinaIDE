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
    }

    @Test
    fun `rejects database attachment and administrative statements`() {
        assertThat(PluginSqlPolicy.isAllowedMutation("ATTACH DATABASE '/data/data/host/private.db' AS host"))
            .isFalse()
        assertThat(PluginSqlPolicy.isAllowedQuery("PRAGMA database_list")).isFalse()
        assertThat(PluginSqlPolicy.isAllowedMutation("VACUUM INTO '/storage/emulated/0/leak.db'"))
            .isFalse()
        assertThat(PluginSqlPolicy.isAllowedQuery("SELECT load_extension('payload.so')")).isFalse()
    }

    @Test
    fun `rejects statement of the wrong category and oversized SQL`() {
        assertThat(PluginSqlPolicy.isAllowedQuery("DELETE FROM notes")).isFalse()
        assertThat(PluginSqlPolicy.isAllowedMutation("SELECT * FROM notes")).isFalse()
        assertThat(PluginSqlPolicy.isAllowedQuery("SELECT " + "x".repeat(70 * 1024))).isFalse()
    }
}
