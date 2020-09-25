package com.github.yueeng.moebooru

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.paging.*
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.room.withTransaction
import androidx.savedstate.SavedStateRegistryOwner
import com.github.yueeng.moebooru.databinding.FragmentSavedBinding
import com.github.yueeng.moebooru.databinding.ListStringItemBinding
import com.github.yueeng.moebooru.databinding.QueryTagItemBinding
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexboxLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.math.min


class SavedViewModel(handle: SavedStateHandle) : ViewModel() {
    val pinned = Pager(PagingConfig(20, enablePlaceholders = false)) { Db.tags.pagingTagsWithIndex(true) }.flow
        .map { it.insertHeaderItem(DbTag(0, MainApplication.instance().getString(R.string.saved_pin), "")) }.cachedIn(viewModelScope)
    val saved = Pager(PagingConfig(20, enablePlaceholders = false)) { Db.tags.pagingTags(false) }.flow
        .map { it.insertHeaderItem(DbTag(0, MainApplication.instance().getString(R.string.saved_tags), "")) }.cachedIn(viewModelScope)
    val edit = handle.getLiveData<Boolean>("edit")
}

class SavedViewModelFactory(owner: SavedStateRegistryOwner, defaultArgs: Bundle?) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = SavedViewModel(handle) as T
}

class SavedFragment : Fragment() {
    private val viewModel: SavedViewModel by sharedViewModels({ "saved" }) { SavedViewModelFactory(this, null) }
    private val pinAdapter by lazy { SavedAdapter() }
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
                    pinAdapter.notifyItemRangeChanged(0, pinAdapter.itemCount)
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                }
            }
            binding.swipe.setOnRefreshListener {
                pinAdapter.refresh()
                adapter.refresh()
            }
            lifecycleScope.launchWhenCreated {
                pinAdapter.loadStateFlow.collectLatest {
                    binding.swipe.isRefreshing = it.refresh is LoadState.Loading
                }
            }
            lifecycleScope.launchWhenCreated {
                adapter.loadStateFlow.collectLatest {
                    binding.swipe.isRefreshing = it.refresh is LoadState.Loading
                }
            }
            (binding.recycler.layoutManager as? FlexboxLayoutManager)?.flexDirection = FlexDirection.ROW
            binding.recycler.adapter = ConcatAdapter(pinAdapter, adapter)
            lifecycleScope.launchWhenCreated {
                viewModel.pinned.collectLatest { pinAdapter.submitData(it) }
            }
            lifecycleScope.launchWhenCreated {
                viewModel.saved.collectLatest { adapter.submitData(it) }
            }
            ItemTouchHelper(object : ItemTouchHelper.Callback() {
                override fun getMovementFlags(view: RecyclerView, holder: RecyclerView.ViewHolder): Int =
                    if (viewModel.edit.value == true && (holder as? SavedHolder)?.tag?.pin == true) makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END, 0)
                    else 0

                override fun onMove(view: RecyclerView, holder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean =
                    (holder as? SavedHolder)?.tag?.takeIf { it.pin }?.let {
                        lifecycleScope.launchWhenCreated {
                            withContext(Dispatchers.IO) {
                                Db.db.withTransaction {
                                    pinAdapter.snapshot().toMutableList().apply {
                                        removeAt(holder.bindingAdapterPosition)
                                        add(min(size, target.bindingAdapterPosition), holder.tag)
                                    }.filter { it?.id != 0L }.mapIndexed { index, tag -> DbTagOrder(tag!!.tag, index + 1) }.sortedBy { it.index }.forEach {
                                        Db.tags.insertOrder(it)
                                    }
                                }
                            }
                        }
                        true
                    } ?: false

                override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit

            }).attachToRecyclerView(binding.recycler)
            binding.button1.setOnClickListener {
                val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), it, "shared_element_container")
                startActivity(Intent(requireContext(), QueryActivity::class.java), options.toBundle())
            }
        }.root

    inner class SavedHolder(private val binding: QueryTagItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setCardBackgroundColor(randomColor())
            binding.root.setOnClickListener {
                val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), it, "shared_element_container")
                startActivity(
                    if (viewModel.edit.value == true) Intent(requireContext(), QueryActivity::class.java).putExtra("query", Q(tag.tag)).putExtra("id", tag.id)
                    else Intent(requireContext(), ListActivity::class.java).putExtra("query", Q(tag.tag)),
                    options.toBundle()
                )
            }
            binding.button1.setOnClickListener {
                lifecycleScope.launchWhenCreated {
                    tag.pin = !tag.pin
                    tag.create = Date()
                    Db.db.withTransaction {
                        Db.tags.updateTag(tag)
                        if (!tag.pin) Db.tags.deleteOrder(tag.tag)
                    }
                }
            }
            binding.button2.setOnClickListener {
                lifecycleScope.launchWhenCreated {
                    Db.db.withTransaction {
                        Db.tags.deleteTag(tag)
                        Db.tags.deleteOrder(tag.tag)
                    }
                }
            }
        }

        lateinit var tag: DbTag
        fun bind(tag: DbTag) {
            this.tag = tag
            binding.text1.text = tag.name
            binding.button1.setImageResource(if (tag.pin) R.drawable.ic_remove else R.drawable.ic_add)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                binding.root.tooltipText = tag.tag
            }
            binding.button1.isVisible = viewModel.edit.value == true
            binding.button2.isVisible = viewModel.edit.value == true
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
            1, 2 -> SavedHolder(QueryTagItemBinding.inflate(layoutInflater, parent, false))
            else -> throw IllegalArgumentException()
        }
    }
}