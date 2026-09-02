package za.bleep.personal

import android.app.Application
import za.bleep.personal.store.Vault

class BleepApp : Application() {
    lateinit var vault: Vault
        private set

    override fun onCreate() {
        super.onCreate()
        Security.insertProvider()
        vault = Vault(this)
    }
}

private object Security {
    fun insertProvider() {
        java.security.Security.removeProvider("BC")
        java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
    }
}
