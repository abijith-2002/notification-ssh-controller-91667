androidApplication {
    namespace = "org.example.app"

    dependencies {
        // AndroidX core and appcompat
        implementation("androidx.core:core-ktx:1.13.1")
        implementation("androidx.appcompat:appcompat:1.7.0")

        // Material Design 3
        implementation("com.google.android.material:material:1.12.0")

        // Activity KTX
        implementation("androidx.activity:activity-ktx:1.9.3")

        // Lifecycle ViewModel KTX
        implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")

        // RecyclerView for app list
        implementation("androidx.recyclerview:recyclerview:1.3.2")

        // DataStore Preferences for lightweight settings storage
        implementation("androidx.datastore:datastore-preferences:1.1.1")

        // AndroidX Security Crypto for encrypted storage
        implementation("androidx.security:security-crypto:1.1.0-alpha06")

        // JSch for SSH functionality
        implementation("com.jcraft:jsch:0.1.55")

        // Project modules
        implementation(project(":utilities"))
    }

    testing {
        dependencies {
            implementation("junit:junit:4.13.2")
        }
    }


}
