package com.wuxianggujun.tinaide.core.debug.di

import com.wuxianggujun.tinaide.core.debug.BreakpointStore
import org.koin.dsl.module

val debugModule = module {
    single { BreakpointStore() }
}
