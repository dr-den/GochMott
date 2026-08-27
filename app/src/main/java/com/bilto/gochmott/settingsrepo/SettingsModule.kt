package com.bilto.gochmott.settingsrepo

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {
    @Singleton
    @Binds
    abstract fun bindSettingRepository(concreteImp: SettingsRepositoryImp): SettingsRepository
}
