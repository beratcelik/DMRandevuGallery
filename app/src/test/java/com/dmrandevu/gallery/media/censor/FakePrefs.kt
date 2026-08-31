package com.dmrandevu.gallery.media.censor

import android.content.SharedPreferences

/**
 * SharedPreferences in a map, for the parts of it [ManualMarks] uses.
 *
 * Robolectric would give the real thing, but it is not in this project and one string per video
 * does not need it.
 */
class FakePrefs : SharedPreferences {

    private val values = mutableMapOf<String, String?>()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()
        private val removals = mutableSetOf<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            pending[key] = value
        }

        override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }

        override fun apply() {
            commit()
        }

        override fun commit(): Boolean {
            removals.forEach { values.remove(it) }
            values.putAll(pending)
            return true
        }

        override fun clear(): SharedPreferences.Editor = apply { values.clear() }

        // Not used by ManualMarks; present because the interface demands them.
        override fun putStringSet(key: String, values: MutableSet<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String?, defValue: Int) = defValue
    override fun getLong(key: String?, defValue: Long) = defValue
    override fun getFloat(key: String?, defValue: Float) = defValue
    override fun getBoolean(key: String?, defValue: Boolean) = defValue
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit
}
