package com.github.yueeng.moebooru

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.recyclerview.widget.*
import androidx.room.withTransaction
import androidx.savedstate.SavedStateRegistryOwner
import androidx.transition.TransitionManager
import com.github.yueeng.moebooru.databinding.*
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import java.util.*

class QueryActivity : MoeActivity(R.layout.activity_main) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? QueryFragment
            ?: QueryFragment().apply { arguments = intent.extras }
        supportFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }
}

class QueryViewModel(handle: SavedStateHandle, args: Bundle?) : ViewModel() {
    val query = handle.getLiveData("query", args?.getParcelable("query") ?: Q())
    val id = handle.getLiveData("id", args?.getLong("id"))
    val name = handle.getLiveData<String>("name")
}

class QueryViewModelFactory(owner: SavedStateRegistryOwner, private val args: Bundle?) : AbstractSavedStateViewModelFactory(owner, args) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = QueryViewModel(handle, args) as T
}

class QueryFragment : Fragment() {
    val viewModel: QueryViewModel by viewModels { QueryViewModelFactory(this, arguments) }
    private val adapter by lazy { QueryAdapter() }

    @SuppressLint("RestrictedApi")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentQueryBinding.inflate(inflater, container, false).also { binding ->
            (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
            requireActivity().title = getString(R.string.app_name)
            lifecycleScope.launchWhenCreated {
                viewModel.name.asFlow().mapNotNull { it }.collectLatest { requireActivity().title = it }
            }
            lifecycleScope.launchWhenCreated {
                viewModel.id.asFlow().mapNotNull { it }.filter { it != 0L }.collectLatest { id ->
                    Db.tags.tag(id)?.let { viewModel.name.postValue(it.name) }
                }
            }
            binding.bottomAppBar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.search -> true.also {
                        startActivity(Intent(requireContext(), ListActivity::class.java).putExtra("query", viewModel.query.value))
                    }
                    R.id.save -> true.also { save() }
                    else -> super.onOptionsItemSelected(item)
                }
            }
            binding.recycler.adapter = adapter.apply { submitList() }
            ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
                override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false
                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    adapter.remove(adapter.data.toList()[viewHolder.bindingAdapterPosition].first)
                }
            }).attachToRecyclerView(binding.recycler)
            binding.button1.setOnClickListener {
                val data = Q.cheats.map { i -> mapOf("k" to i.key, "n" to getString(i.value.first), "d" to getString(i.value.second)) }
                val adapter = SimpleAdapter(
                    requireActivity(), data, R.layout.simple_list_item_2, arrayOf("n", "d"), intArrayOf(R.id.text1, R.id.text2)
                )
                MaterialAlertDialogBuilder(requireContext())
                    .setAdapter(adapter) { _, w ->
                        edit(data[w]["k"])
                    }
                    .create().show()
            }
        }.root

    private fun save() = lifecycleScope.launchWhenCreated {
        viewModel.query.value?.toString()?.takeIf { it.isNotBlank() }?.let { tag ->
            val view = QuerySavedBinding.inflate(layoutInflater)
            val saved = viewModel.id.value?.takeIf { it != 0L }?.let { Db.tags.tag(it) }
            view.input1.hint = tag
            view.edit1.setText(saved?.name)
            view.switch1.isChecked = saved?.pin ?: false
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.saved_title)
                .setView(view.root)
                .setPositiveButton(R.string.app_ok) { _, _ ->
                    ProcessLifecycleOwner.get().lifecycleScope.launchWhenCreated {
                        Db.db.withTransaction {
                            val name = view.edit1.text?.toString()?.takeIf { it.isNotBlank() } ?: tag.toTitleCase()
                            val pin = view.switch1.isChecked
                            if (saved != null) {
                                Db.tags.updateTag(saved.update(tag, name, pin))
                            } else {
                                viewModel.id.postValue(Db.tags.insertTag(DbTag(0, tag, name, pin)))
                            }
                            viewModel.name.postValue(name)
                        }
                    }
                }
                .setNegativeButton(R.string.app_cancel, null)
                .create().show()
        } ?: Snackbar.make(requireView(), R.string.saved_empty, Snackbar.LENGTH_SHORT)
            .setAnchorView(R.id.button1)
            .setAction(R.string.app_ok) {}
            .show()
    }

    fun edit(key: String?) {
        when (key) {
            "user", "md5", "parent", "pool", "source", "keyword" -> string(key)
            "id", "width", "height", "score" -> int<Int>(key)
            "mpixels" -> int<Float>(key)
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
        view.text1.addTextChangedListener {
            view.input1.isErrorEnabled = it.isNullOrEmpty()
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle(key).setView(view.root)
            .setCancelable(false)
            .setPositiveButton(R.string.app_ok, null)
            .setNegativeButton(R.string.app_cancel, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                        val txt = view.text1.text.toString().trim()
                        if (txt.isEmpty()) {
                            view.input1.isErrorEnabled = true
                            view.input1.error = getString(R.string.query_empty)
                            return@setOnClickListener
                        }
                        adapter.add(key, txt)
                        dismiss()
                    }
                }
            }
            .show()
    }

    private inline fun <reified T : Number> int(key: String) {
        val view = QuerySheetIntBinding.inflate(layoutInflater)
        view.chipGroup.setOnCheckedChangeListener { _, _ ->
            TransitionManager.beginDelayedTransition(view.root)
            view.input2.isVisible = view.chipGroup.checkedChip?.tag == Q.Value.Op.bt.value
        }
        view.edit1.addTextChangedListener {
            view.input1.isErrorEnabled = it.isNullOrEmpty()
        }
        view.edit2.addTextChangedListener {
            view.input1.isErrorEnabled = it.isNullOrEmpty()
        }
        @Suppress("UNCHECKED_CAST")
        val default = adapter.data[key] as? Q.Value<T>
        if (default != null) {
            view.edit1.setText(default.v1string)
            view.edit2.setText(default.v2string)
            view.chipGroup.findViewWithTag<Chip>(default.op.value)?.isChecked = true
        } else {
            view.chipGroup.children.mapNotNull { it as Chip }.firstOrNull()?.isChecked = true
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle(key).setView(view.root)
            .setCancelable(false)
            .setPositiveButton(R.string.app_ok, null)
            .setNegativeButton(R.string.app_cancel, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                        val chip = view.chipGroup.checkedChip
                        val op = Q.Value.Op.values().firstOrNull { it.value == chip?.tag }
                        if (op == null) {
                            Snackbar.make(view.root, R.string.query_empty, Snackbar.LENGTH_SHORT)
                                .setAction(R.string.app_ok) {}
                                .show()
                            return@setOnClickListener
                        }
                        val v1 = view.edit1.text?.toString()?.let {
                            when (T::class) {
                                Int::class -> it.toIntOrNull()
                                Float::class -> it.toFloatOrNull()
                                else -> throw IllegalArgumentException()
                            }
                        }
                        if (v1 == null) {
                            view.input1.isErrorEnabled = true
                            view.input1.error = getString(R.string.query_empty)
                            return@setOnClickListener
                        }
                        val v2 = view.edit1.text?.toString()?.let {
                            when (T::class) {
                                Int::class -> it.toIntOrNull()
                                Float::class -> it.toFloatOrNull()
                                else -> throw IllegalArgumentException()
                            }
                        }
                        if (op == Q.Value.Op.bt && v2 == null) {
                            view.input2.isErrorEnabled = true
                            view.input2.error = getString(R.string.query_empty)
                            return@setOnClickListener
                        }
                        val value = Q.Value(op, v1, v2)
                        adapter.add(key, value)
                        dismiss()
                    }
                }
            }
            .show()
    }

    private fun date(key: String) {
        val view = QuerySheetDateBinding.inflate(layoutInflater)
        view.chipGroup.setOnCheckedChangeListener { _, _ ->
            TransitionManager.beginDelayedTransition(view.root)
            view.input2.isVisible = view.chipGroup.checkedChip?.tag == Q.Value.Op.bt.value
            view.pick2.isVisible = view.chipGroup.checkedChip?.tag == Q.Value.Op.bt.value
        }
        listOf(view.pick1 to view.edit1, view.pick2 to view.edit2).forEach { pe ->
            pe.first.setOnClickListener {
                val current = Q.formatter.tryParse(pe.second.text.toString()) ?: Date()
                val pick = DatePicker(requireContext()).apply {
                    minDate = moeCreateTime.milliseconds
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
        view.edit1.addTextChangedListener {
            view.input1.isErrorEnabled = Q.formatter.tryParse(it.toString()) == null
        }
        view.edit2.addTextChangedListener {
            view.input1.isErrorEnabled = Q.formatter.tryParse(it.toString()) == null
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
            .setCancelable(false)
            .setPositiveButton(R.string.app_ok, null)
            .setNegativeButton(R.string.app_cancel, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                        val chip = view.chipGroup.checkedChip
                        val op = Q.Value.Op.values().firstOrNull { it.value == chip?.tag }
                        if (op == null) {
                            Snackbar.make(view.root, R.string.query_empty, Snackbar.LENGTH_SHORT)
                                .setAction(R.string.app_ok) {}
                                .show()
                            return@setOnClickListener
                        }
                        val v1 = Q.formatter.tryParse(view.edit1.text.toString())
                        if (v1 == null) {
                            view.input1.isErrorEnabled = true
                            view.input1.error = getString(R.string.query_empty)
                            return@setOnClickListener
                        }
                        val v2 = Q.formatter.tryParse(view.edit2.text.toString())
                        if (op == Q.Value.Op.bt && v2 == null) {
                            view.input2.isErrorEnabled = true
                            view.input2.error = getString(R.string.query_empty)
                            return@setOnClickListener
                        }
                        val value = Q.Value(op, v1, v2)
                        adapter.add(key, value)
                        dismiss()
                    }
                }
            }
            .show()
    }

    private fun vote(key: String) {
        val view = QuerySheetVoteBinding.inflate(layoutInflater)
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
        view.edit1.addTextChangedListener {
            view.input1.isErrorEnabled = it.isNullOrEmpty()
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle(key).setView(view.root)
            .setCancelable(false)
            .setPositiveButton(R.string.app_ok, null)
            .setNegativeButton(R.string.app_cancel, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                        val chip = view.chipGroup.checkedChip
                        val op = Q.Value.Op.values().firstOrNull { it.value == chip?.tag }
                        if (op == null) {
                            Snackbar.make(view.root, R.string.query_empty, Snackbar.LENGTH_SHORT)
                                .setAction(R.string.app_ok) {}
                                .show()
                            return@setOnClickListener
                        }
                        val ex = view.edit1.text?.toString()?.takeIf { it.isNotBlank() }
                        if (ex == null) {
                            view.input1.isErrorEnabled = true
                            view.input1.error = getString(R.string.query_empty)
                            return@setOnClickListener
                        }
                        val v1 = view.chip1.checkedChip?.tag?.toString()?.toIntOrNull()
                        if (v1 == null) {
                            Snackbar.make(view.root, R.string.query_empty, Snackbar.LENGTH_SHORT)
                                .setAction(R.string.app_ok) {}
                                .show()
                            return@setOnClickListener
                        }
                        val v2 = view.chip2.checkedChip?.tag?.toString()?.toIntOrNull()
                        if (op == Q.Value.Op.bt && v2 == null) {
                            Snackbar.make(view.root, R.string.query_empty, Snackbar.LENGTH_SHORT)
                                .setAction(R.string.app_ok) {}
                                .show()
                            return@setOnClickListener
                        }
                        val value = Q.Value(op, v1, v2, ex)
                        adapter.add(key, value)
                        dismiss()
                    }
                }
            }.show()

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

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
                super.getView(position, convertView, parent).apply {
                    findViewById<RadioButton>(R.id.radio).isChecked = position == checked
                }
        }
        val value = when (val v = this@QueryFragment.adapter.data[key]) {
            is Q.Order -> v.value
            is Q.Rating -> v.value
            else -> null
        }
        adapter.checked = data.indexOfFirst { it["n"] == value }
        MaterialAlertDialogBuilder(requireActivity())
            .setCancelable(false)
            .setTitle(Q.cheats[key]!!.first)
            .setSingleChoiceItems(adapter, 0) { _, w -> adapter.checked = w }
            .setPositiveButton(R.string.app_ok, null)
            .setNegativeButton(R.string.app_cancel, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                        if (adapter.checked == -1) {
                            Snackbar.make(listView, R.string.query_empty, Snackbar.LENGTH_SHORT)
                                .setAction(R.string.app_ok) {}
                                .show()
                            return@setOnClickListener
                        }
                        val selected = data[adapter.checked]["n"]
                        val choice = when (key) {
                            "order" -> Q.Order.values().first { it.value == selected }
                            "rating" -> Q.Rating.values().first { it.value == selected }
                            else -> null
                        }
                        this@QueryFragment.adapter.add(key, choice!!)
                        dismiss()
                    }
                }
            }
            .show()
    }

    class QueryHolder(val binding: QueryItemBinding) : RecyclerView.ViewHolder(binding.root)
    inner class QueryAdapter : RecyclerView.Adapter<QueryHolder>() {
        private val differ = AsyncListDiffer(
            AdapterListUpdateCallback(this),
            AsyncDifferConfig.Builder(diffCallback<Pair<String, Any>> { old, new -> old.first == new.first }).build()
        )
        val data get() = viewModel.query.value!!.map

        fun submitList() {
            differ.submitList(viewModel.query.value!!.map.toList())
        }

        fun add(k: String, v: Any) {
            viewModel.query.value!!.map[k] = v
            differ.submitList(viewModel.query.value!!.map.toList())
        }

        fun remove(k: String) {
            viewModel.query.value!!.map.remove(k)
            differ.submitList(viewModel.query.value!!.map.toList())
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueryHolder = QueryHolder(QueryItemBinding.inflate(layoutInflater, parent, false)).apply {
            binding.root.setOnClickListener {
                edit(differ.currentList[bindingAdapterPosition].first)
            }
        }

        override fun onBindViewHolder(holder: QueryHolder, position: Int) {
            val item = differ.currentList[position]
            holder.binding.text1.text = item.first
            holder.binding.text2.text = "${item.second}"
        }

        override fun getItemCount(): Int = differ.currentList.size
    }
}