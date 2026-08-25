package com.wuxianggujun.tinaide.project

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProjectIdentityTest {
    @Test
    fun `accepts generated and legacy safe identities`() {
        assertThat(ProjectIdentity.isValid(ProjectIdentity.create())).isTrue()
        assertThat(ProjectIdentity.isValid("legacy-project_1.0")).isTrue()
    }

    @Test
    fun `rejects path components and oversized identities`() {
        assertThat(ProjectIdentity.isValid("../workspace")).isFalse()
        assertThat(ProjectIdentity.isValid("folder/project")).isFalse()
        assertThat(ProjectIdentity.isValid("folder\\project")).isFalse()
        assertThat(ProjectIdentity.isValid("/absolute")).isFalse()
        assertThat(ProjectIdentity.isValid("x".repeat(129))).isFalse()
    }
}
