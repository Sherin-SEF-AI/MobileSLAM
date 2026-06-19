package com.mappilot.core.common.di

import com.mappilot.core.common.bus.EventBus
import com.mappilot.core.common.bus.SharedFlowEventBus
import com.mappilot.core.common.config.ConfigProvider
import com.mappilot.core.common.config.DefaultConfigProvider
import com.mappilot.core.common.dispatcher.DefaultDispatcherProvider
import com.mappilot.core.common.dispatcher.DispatcherProvider
import com.mappilot.core.common.time.SystemTimeSource
import com.mappilot.core.common.time.TimeSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class CommonModule {

    @Binds
    abstract fun bindTimeSource(impl: SystemTimeSource): TimeSource

    @Binds
    abstract fun bindDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider

    @Binds
    abstract fun bindEventBus(impl: SharedFlowEventBus): EventBus

    @Binds
    abstract fun bindConfigProvider(impl: DefaultConfigProvider): ConfigProvider
}
