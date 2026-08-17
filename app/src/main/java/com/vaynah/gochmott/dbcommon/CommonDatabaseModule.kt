package com.vaynah.gochmott.dbcommon

import android.content.Context
import androidx.room.Room
import com.vaynah.gochmott.settingsrepo.SettingsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class CommonDatabaseModule {

    @Singleton
    @Provides
    fun provideDb(@ApplicationContext context: Context): CommonDatabase {
        return Room
            .databaseBuilder(context, CommonDatabase::class.java, "common.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .enableMultiInstanceInvalidation()
            .build()
    }
}


@Module
@InstallIn(SingletonComponent::class)
class CommonDaoModule {
    @Provides
    fun provideSettingDao(db: CommonDatabase): SettingsDao {
        return db.settingsDao()
    }
}





