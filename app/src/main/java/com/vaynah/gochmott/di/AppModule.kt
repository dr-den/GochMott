package com.vaynah.gochmott.di

import android.content.Context
import com.vaynah.gochmott.db.DatabaseHelper
import com.vaynah.gochmott.repository.DictRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabaseHelper(@ApplicationContext context: Context): DatabaseHelper =
        DatabaseHelper(context)

    @Provides
    @Singleton
    fun provideDictRepository(dbHelper: DatabaseHelper): DictRepository =
        DictRepository(dbHelper)
}
