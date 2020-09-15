package com.github.yueeng.moebooru

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.paging.*
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import com.github.yueeng.moebooru.databinding.FragmentSavedBinding
import com.github.yueeng.moebooru.databinding.ListStringItemBinding
import com.github.yueeng.moebooru.databinding.QueryTagItemBinding
import com.google.android.flexbox.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import java.util.*


class SavedViewModel(handle: SavedStateHandle) : ViewModel() {
    val saved = Pager(PagingConfig(20, enablePlaceholders = false)) { Db.tags.pagingTags() }.flow
        .map {
            it.insertHeaderItem(DbTag(0, "Pin", "")).insertSeparators { dbTag, dbTag2 ->
                if (dbTag?.pin == true && dbTag2?.pin == false)
                    DbTag(0, "UnPin", "")
                else null
            }
        }
    val edit = handle.getLiveData<Boolean>("edit")
}

class SavedViewModelFactory(owner: SavedStateRegistryOwner, defaultArgs: Bundle?) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = SavedViewModel(handle) as T
}

class SavedActivity : AppCompatActivity(R.layout.activity_container) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? SavedFragment ?: SavedFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment).commit()
    }
}

class SavedFragment : Fragment() {
    private val viewModel: SavedViewModel by sharedViewModels({ "saved" }) { SavedViewModelFactory(this, null) }
    private val adapter by lazy { SavedAdapter() }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentSavedBinding.inflate(inflater, container, false).also { binding ->
            binding.toolbar.setTitle(R.string.saved_title)
            binding.toolbar.setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.edit -> true.also {
                        viewModel.edit.postValue(!(viewModel.edit.value ?: false))
                    }
                    else -> false
                }
            }
            lifecycleScope.launchWhenCreated {
                viewModel.edit.asFlow().collectLatest {
                    binding.toolbar.menu.findItem(R.id.edit).setIcon(if (it) R.drawable.ic_done else R.drawable.ic_edit)
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                }
            }
            binding.swipe.setOnRefreshListener { adapter.refresh() }
            lifecycleScope.launchWhenCreated {
                adapter.loadStateFlow.collectLatest {
                    binding.swipe.isRefreshing = it.refresh is LoadState.Loading
                }
            }
            (binding.recycler.layoutManager as? FlexboxLayoutManager)?.apply {
                flexWrap = FlexWrap.WRAP
                flexDirection = FlexDirection.ROW
                alignItems = AlignItems.FLEX_START
                justifyContent = JustifyContent.FLEX_START
            }
            binding.recycler.adapter = adapter
            lifecycleScope.launchWhenCreated {
                viewModel.saved.collectLatest { adapter.submitData(it) }
            }
            binding.button1.setOnClickListener {
                startActivity(Intent(requireContext(), QueryActivity::class.java))
            }
        }.root

    inner class SavedHolder(val binding: QueryTagItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setCardBackgroundColor(randomColor())
        }

        var tag: DbTag? = null
        fun bind(tag: DbTag) {
            this.tag = tag
            binding.text1.text = tag.name
            binding.button2.setImageResource(if (tag.pin) R.drawable.ic_remove else R.drawable.ic_add)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                binding.root.tooltipText = tag.tag
            }
            binding.button1.isVisible = viewModel.edit.value == true
            binding.button2.isVisible = viewModel.edit.value == true
            binding.button3.isVisible = viewModel.edit.value == true
        }
    }

    inner class HeaderHolder(private val binding: ListStringItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(tag: DbTag) {
            binding.text1.text = tag.tag
        }
    }

    inner class SavedAdapter : PagingDataAdapter<DbTag, RecyclerView.ViewHolder>(diffCallback { old, new -> old.id == new.id }) {
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is SavedHolder -> holder.bind(getItem(position)!!)
                is HeaderHolder -> holder.bind(getItem(position)!!)
            }
        }

        override fun getItemViewType(position: Int): Int = getItem(position).let { item ->
            when {
                item == null -> 0
                item.id == 0L -> 0
                item.pin -> 1
                else -> 2
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            0 -> HeaderHolder(ListStringItemBinding.inflate(layoutInflater, parent, false))
            1, 2 -> {
                SavedHolder(QueryTagItemBinding.inflate(layoutInflater, parent, false)).apply {
                    binding.root.setOnClickListener {
                        val item = getItem(bindingAdapterPosition) ?: return@setOnClickListener
                        startActivity(Intent(requireContext(), ListActivity::class.java).putExtra("query", Q(item.tag)))
                    }
                    binding.button1.setOnClickListener {
                        val item = getItem(bindingAdapterPosition) ?: return@setOnClickListener
                        startActivity(
                            Intent(requireContext(), QueryActivity::class.java)
                                .putExtra("query", Q(item.tag))
                                .putExtra("id", item.id)
                        )
                    }
                    binding.button2.setOnClickListener {
                        val item = getItem(bindingAdapterPosition) ?: return@setOnClickListener
                        lifecycleScope.launchWhenCreated {
                            item.pin = !item.pin
                            item.create = Date()
                            Db.tags.updateTag(item)
                        }

                    }
                    binding.button3.setOnClickListener {
                        val item = getItem(bindingAdapterPosition) ?: return@setOnClickListener
                        lifecycleScope.launchWhenCreated {
                            Db.tags.deleteTag(item)
                        }
                    }
                }
            }
            else -> throw IllegalArgumentException()
        }
    }
}