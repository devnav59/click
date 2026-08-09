package com.autoclick.replay.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.autoclick.replay.databinding.ItemTaskBinding
import com.autoclick.replay.model.Task

class TaskAdapter(
    private val onOpen: (Task)->Unit,
    private val onPlay: (Task)->Unit,
    private val onDelete: (Task)->Unit
): RecyclerView.Adapter<TaskAdapter.VH>() {
    private var items = listOf<Task>()
    fun submit(list: List<Task>) { items = list; notifyDataSetChanged() }
    class VH(val b: ItemTaskBinding): RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }
    override fun onBindViewHolder(h: VH, pos: Int) {
        val t = items[pos]
        h.b.txtName.text = t.name
        h.b.txtPkg.text = if (t.targetPackage.isEmpty()) "همهٔ اپ‌ها" else t.targetPackage
        h.b.txtCount.text = "${t.actions.size} اقدام"
        h.b.btnOpen.setOnClickListener { onOpen(t) }
        h.b.btnPlay.setOnClickListener { onPlay(t) }
        h.b.btnDelete.setOnClickListener { onDelete(t) }
    }
    override fun getItemCount() = items.size
}
