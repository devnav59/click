package com.autoclick.replay.util

import android.content.Context
import com.autoclick.replay.model.Task
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object TaskRepository {
    private const val PREF = "autoclick_tasks"
    private const val KEY = "tasks_json"
    private val gson = Gson()

    fun load(context: Context): MutableList<Task> {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val json = sp.getString(KEY, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<Task>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) { mutableListOf() }
    }

    fun save(context: Context, tasks: List<Task>) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.edit().putString(KEY, gson.toJson(tasks)).apply()
    }

    fun upsert(context: Context, task: Task) {
        val list = load(context)
        val idx = list.indexOfFirst { it.id == task.id }
        if (idx >= 0) list[idx] = task else list.add(task)
        save(context, list)
    }

    fun delete(context: Context, id: String) {
        val list = load(context)
        list.removeAll { it.id == id }
        save(context, list)
    }

    fun get(context: Context, id: String): Task? = load(context).find { it.id == id }
}
