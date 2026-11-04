package com.storageguard.app

import android.app.Application
import com.storageguard.app.data.PreferenceStore
import com.storageguard.app.data.StorageDatabase
import com.storageguard.app.storage.AndroidStorage

class StorageGuardApplication : Application() {
    val database by lazy { StorageDatabase.open(this) }
    val preferences by lazy { PreferenceStore(this) }
    val storage by lazy { AndroidStorage(this) }
}
