package com.bekacodes.pocketagent

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Custom Application class annotated with [@HiltAndroidApp] to initiate Hilt dependency injection.
 */
@HiltAndroidApp
class PocketAgentApplication : Application()
