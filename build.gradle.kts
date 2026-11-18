plugins {
  alias(libs.plugins.android.application) apply false
  // Tidak di-apply: hanya mengunci versi compiler Kotlin yang dipakai Kotlin bawaan AGP 9.
  alias(libs.plugins.kotlin.android) apply false
}
