package dagger.hilt

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class InstallIn(vararg val value: kotlin.reflect.KClass<*>)
