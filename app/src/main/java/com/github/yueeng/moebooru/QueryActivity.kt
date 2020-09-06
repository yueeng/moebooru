package com.github.yueeng.moebooru

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.SimpleAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.*
import androidx.transition.TransitionManager
import com.github.yueeng.moebooru.databinding.FragmentQueryBinding
import com.github.yueeng.moebooru.databinding.QueryItemBinding
import com.github.yueeng.moebooru.databinding.QuerySheetIntBinding
import com.github.yueeng.moebooru.databinding.QuerySheetStringBinding
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class QueryActivity : AppCompatActivity(R.layout.activity_main) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? QueryFragment
            ?: QueryFragment().apply { arguments = intent.extras }
        supportFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }
}

class QueryFragment : Fragment() {
    val query by lazy { arguments?.getParcelable("query") ?: Q() }
    val adapter by lazy { QueryAdapter() }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentQueryBinding.inflate(inflater, container, false).also { binding ->
            binding.recycler.adapter = adapter
            binding.button1.setOnClickListener {
                val data = Q.cheats.map { i -> mapOf("k" to i.key, "n" to getString(i.value.first), "d" to getString(i.value.second)) }
                val adapter = SimpleAdapter(
                    requireActivity(), data, android.R.layout.simple_list_item_2, arrayOf("n", "d"), intArrayOf(android.R.id.text1, android.R.id.text2)
                )
                MaterialAlertDialogBuilder(requireContext())
                    .setAdapter(adapter) { _, w ->
                        edit(data[w]["k"])
                    }
                    .create().show()
            }
        }.root

    fun edit(key: String?) {
        when (key) {
            "user", "md5", "parent", "pool", "source", "keyword" -> string(key)
            "id", "width", "height", "score", "mpixels" -> int(key)
            "date" -> date(key)
            "vote" -> vote(key)
            "rating", "order" -> option(key)
            else -> throw IllegalArgumentException()
        }
    }

    private fun string(key: String) {
        val view = QuerySheetStringBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(requireContext()).setTitle(key).setView(view.root)
            .setPositiveButton(R.string.app_ok) { _, _ -> }
            .setNegativeButton(R.string.app_cancel, null)
            .create().show()
    }

    private fun int(key: String) {
        val view = QuerySheetIntBinding.inflate(layoutInflater)
        view.chipGroup.isSingleSelection = true
        view.chipGroup.setOnCheckedChangeListener { _, id ->
            TransitionManager.beginDelayedTransition(view.root)
            view.input2.isVisible = id == R.id.chip_bt
        }
        @Suppress("UNCHECKED_CAST")
        val default = adapter.data[key] as? Q.Value<Int>
        if (default != null) {
            view.edit1.setText("${default.v1}")
            view.edit2.setText("${default.v2 ?: ""}")
            view.chipGroup.findViewWithTag<Chip>(default.op.value)?.isChecked = true
        } else {
            view.chipEq.isChecked = true
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle(key).setView(view.root)
            .setPositiveButton(R.string.app_ok) { _, _ ->
                val chip = view.chipGroup.findViewById<Chip>(view.chipGroup.checkedChipId)
                val value = Q.Value(
                    Q.Value.Op.values().first { it.value == chip.tag },
                    view.edit1.text.toString().toInt(),
                    view.edit2.text.toString().toIntOrNull()
                )
                adapter.add(key, value)
            }
            .setNegativeButton(R.string.app_cancel, null)
            .create().show()
    }

    private fun date(key: String) {

    }

    private fun vote(key: String) {

    }

    private fun option(key: String) {
        val data = when (key) {
            "order" -> Q.orders.map { "${it.key}" to getString(it.value) }
            "rating" -> Q.ratings.map { "${it.key}" to getString(it.value) }
            else -> throw IllegalArgumentException()
        }.map { mapOf("n" to it.first, "d" to it.second) }
        val adapter = object : SimpleAdapter(
            requireActivity(), data, R.layout.simple_list_item_2_single_choice,
            arrayOf("n", "d"), intArrayOf(android.R.id.text1, android.R.id.text2)
        ) {
            var checked: Int = -1
                set(value) {
                    field = value
                    notifyDataSetChanged()
                }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = super.getView(position, convertView, parent)
                view.findViewById<RadioButton>(R.id.radio).isChecked = position == checked
                return view
            }
        }
        val value = when (val v = this@QueryFragment.adapter.data[key]) {
            is Q.Order -> v.value
            is Q.Rating -> v.value
            else -> null
        }
        adapter.checked = data.indexOfFirst { it["n"] == value }
        MaterialAlertDialogBuilder(requireActivity())
            .setTitle(Q.cheats[key]!!.first)
            .setSingleChoiceItems(adapter, 0) { _, w ->
                adapter.checked = w
            }
            .setPositiveButton(R.string.app_ok) { _, _ ->
                if (adapter.checked > -1) {
                    val selected = data[adapter.checked]["n"]
                    val choice = when (key) {
                        "order" -> Q.Order.values().first { it.value == selected }
                        "rating" -> Q.Rating.values().first { it.value == selected }
                        else -> null
                    }
                    this@QueryFragment.adapter.add(key, choice!!)
                }
            }
            .create().show()
    }

    class QueryHolder(val binding: QueryItemBinding) : RecyclerView.ViewHolder(binding.root)
    inner class QueryAdapter : RecyclerView.Adapter<QueryHolder>() {
        private val differ = object : DiffUtil.ItemCallback<Pair<String, Any>>() {
            override fun areItemsTheSame(oldItem: Pair<String, Any>, newItem: Pair<String, Any>): Boolean = oldItem.first == newItem.first
            override fun areContentsTheSame(oldItem: Pair<String, Any>, newItem: Pair<String, Any>): Boolean = oldItem == newItem
        }
        private val diff = AsyncListDiffer(AdapterListUpdateCallback(this), AsyncDifferConfig.Builder(differ).build())
        val data = query.map.toMutableMap()
        fun add(k: String, v: Any) {
            data[k] = v
            diff.submitList(data.toList())
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueryHolder = QueryHolder(QueryItemBinding.inflate(layoutInflater, parent, false)).apply {
            binding.root.setOnClickListener {
                edit(diff.currentList[bindingAdapterPosition].first)
            }
        }

        override fun onBindViewHolder(holder: QueryHolder, position: Int) {
            val item = diff.currentList[position]
            holder.binding.text1.text = item.first
            holder.binding.text2.text = "${item.second}"
        }

        override fun getItemCount(): Int = diff.currentList.size
    }
}