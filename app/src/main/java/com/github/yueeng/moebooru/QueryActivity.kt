package com.github.yueeng.moebooru

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.*
import androidx.savedstate.SavedStateRegistryOwner
import androidx.transition.TransitionManager
import com.github.yueeng.moebooru.databinding.*
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.*

class QueryActivity : AppCompatActivity(R.layout.activity_main) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? QueryFragment
            ?: QueryFragment().apply { arguments = intent.extras }
        supportFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }
}

class QueryViewModel(handle: SavedStateHandle, args: Bundle?) : ViewModel() {
    val query = handle.getLiveData("query", args?.getParcelable("query") ?: Q())
}

class QueryViewModelFactory(owner: SavedStateRegistryOwner, private val args: Bundle?) : AbstractSavedStateViewModelFactory(owner, args) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = QueryViewModel(handle, args) as T
}

class QueryFragment : Fragment() {
    val viewModel: QueryViewModel by viewModels { QueryViewModelFactory(this, arguments) }
    private val adapter by lazy { QueryAdapter() }
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
        val default = adapter.data[key] as? String
        if (default != null) {
            view.text1.setText(default)
        }
        if (key == "keyword") {
            val adapter = object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1) {
                override fun getFilter(): Filter = filter
                val filter = object : Filter() {
                    override fun performFiltering(constraint: CharSequence?): FilterResults = FilterResults().also { results ->
                        if (constraint?.isNotBlank() == true) {
                            val data = Q.suggest(constraint.toString().trim()).take(10).toList()
                            results.values = data
                            results.count = data.size
                        }
                    }

                    override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                        @Suppress("UNCHECKED_CAST")
                        val data = results?.values as? List<Triple<Int, String, String>>
                        clear()
                        if (data?.isNotEmpty() == true) {
                            addAll(data.map { it.second })
                            notifyDataSetChanged()
                        } else {
                            notifyDataSetInvalidated()
                        }
                    }
                }
            }
            view.text1.setAdapter(adapter)
            view.text1.threshold = 1
            view.text1.setTokenizer(SymbolsTokenizer(setOf(' ')))
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle(key).setView(view.root)
            .setPositiveButton(R.string.app_ok) { _, _ ->
                adapter.add(key, view.text1.text.toString().trim())
            }
            .setNegativeButton(R.string.app_cancel, null)
            .create().show()
    }

    private fun int(key: String) {
        val view = QuerySheetIntBinding.inflate(layoutInflater)
        view.chipGroup.isSingleSelection = true
        view.chipGroup.setOnCheckedChangeListener { _, _ ->
            TransitionManager.beginDelayedTransition(view.root)
            view.input2.isVisible = view.chipGroup.checkedChip?.tag == Q.Value.Op.bt.value
        }
        @Suppress("UNCHECKED_CAST")
        val default = adapter.data[key] as? Q.Value<Int>
        if (default != null) {
            view.edit1.setText(default.v1string)
            view.edit2.setText(default.v2string)
            view.chipGroup.findViewWithTag<Chip>(default.op.value)?.isChecked = true
        } else {
            view.chipGroup.children.mapNotNull { it as Chip }.firstOrNull()?.isChecked = true
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle(key).setView(view.root)
            .setPositiveButton(R.string.app_ok) { _, _ ->
                val chip = view.chipGroup.checkedChip
                val value = Q.Value(
                    Q.Value.Op.values().first { it.value == chip?.tag },
                    view.edit1.text.toString().toInt(),
                    view.edit2.text.toString().toIntOrNull()
                )
                adapter.add(key, value)
            }
            .setNegativeButton(R.string.app_cancel, null)
            .create().show()
    }

    private fun date(key: String) {
        val view = QuerySheetDateBinding.inflate(layoutInflater)
        view.chipGroup.isSingleSelection = true
        view.chipGroup.setOnCheckedChangeListener { _, _ ->
            TransitionManager.beginDelayedTransition(view.root)
            view.input2.isVisible = view.chipGroup.checkedChip?.tag == Q.Value.Op.bt.value
            view.pick2.isVisible = view.chipGroup.checkedChip?.tag == Q.Value.Op.bt.value
        }
        listOf(view.pick1 to view.edit1, view.pick2 to view.edit2).forEach { pe ->
            pe.first.setOnClickListener {
                val current = Q.formatter.tryParse(pe.second.text.toString()) ?: Date()
                val pick = DatePicker(requireContext()).apply {
                    minDate = moe_create_time.milliseconds
                    maxDate = Calendar.getInstance().milliseconds
                    date = Calendar.getInstance().apply { time = current }
                }
                MaterialAlertDialogBuilder(requireContext()).setView(pick)
                    .setPositiveButton(R.string.app_ok) { _, _ ->
                        pe.second.setText(pick.date.format(Q.formatter))
                    }
                    .setNegativeButton(R.string.app_cancel, null)
                    .create().show()
            }
        }
        @Suppress("UNCHECKED_CAST")
        val default = adapter.data[key] as? Q.Value<Date>
        if (default != null) {
            view.edit1.setText(default.v1string)
            view.edit2.setText(default.v2string)
            view.chipGroup.findViewWithTag<Chip>(default.op.value)?.isChecked = true
        } else {
            view.chipGroup.children.mapNotNull { it as Chip }.firstOrNull()?.isChecked = true
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle(key).setView(view.root)
            .setPositiveButton(R.string.app_ok) { _, _ ->
                val chip = view.chipGroup.checkedChip
                val value = Q.Value(
                    Q.Value.Op.values().first { it.value == chip?.tag },
                    Q.formatter.tryParse(view.edit1.text.toString()) ?: Date(),
                    Q.formatter.tryParse(view.edit2.text.toString()) ?: Date()
                )
                adapter.add(key, value)
            }
            .setNegativeButton(R.string.app_cancel, null)
            .create().show()
    }

    private fun vote(key: String) {
        val view = QuerySheetVoteBinding.inflate(layoutInflater)
        view.chipGroup.isSingleSelection = true
        view.chipGroup.setOnCheckedChangeListener { _, _ ->
            TransitionManager.beginDelayedTransition(view.root)
            view.chip2.isVisible = view.chipGroup.checkedChip?.tag == Q.Value.Op.bt.value
        }
        @Suppress("UNCHECKED_CAST")
        val default = adapter.data[key] as? Q.Value<Int>
        if (default != null) {
            view.edit1.setText(default.ex)
            view.chipGroup.findViewWithTag<Chip>(default.op.value)?.isChecked = true
            view.chip1.findViewWithTag<Chip>(default.v1string)?.isChecked = true
            view.chip2.findViewWithTag<Chip>(default.v2string)?.isChecked = true
        } else {
            view.chipGroup.children.mapNotNull { it as Chip }.firstOrNull()?.isChecked = true
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle(key).setView(view.root)
            .setPositiveButton(R.string.app_ok) { _, _ ->
                val chip = view.chipGroup.checkedChip
                val chip1 = view.chip1.checkedChip
                val chip2 = view.chip2.checkedChip
                val value = Q.Value(
                    Q.Value.Op.values().first { it.value == chip?.tag },
                    chip1?.tag.toString().toInt(),
                    chip2?.tag.toString().toIntOrNull(),
                    view.edit1.text.toString().takeIf { it.isNotEmpty() }
                )
                adapter.add(key, value)
            }
            .setNegativeButton(R.string.app_cancel, null)
            .create().show()

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
        val data get() = viewModel.query.value!!.map
        fun add(k: String, v: Any) {
            viewModel.query.value!!.map[k] = v
            diff.submitList(viewModel.query.value!!.map.toList())
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