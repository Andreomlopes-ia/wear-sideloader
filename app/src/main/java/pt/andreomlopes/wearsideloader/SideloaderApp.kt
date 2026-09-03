package pt.andreomlopes.wearsideloader

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class SideloaderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // ADB pairing needs TLSv1.3, which the platform provider lacks on older releases.
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
    }
}
