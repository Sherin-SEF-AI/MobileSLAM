package com.mappilot.slam.arcore

import com.mappilot.slam.core.SlamEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SlamModule {
    @Binds
    abstract fun bindSlamEngine(impl: ArcoreSlamEngine): SlamEngine
}
