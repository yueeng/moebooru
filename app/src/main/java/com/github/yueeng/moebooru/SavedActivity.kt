package com.github.yueeng.moebooru

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import com.github.yueeng.moebooru.databinding.FragmentSavedBinding
import com.github.yueeng.moebooru.databinding.QueryTagItemBinding
import kotlinx.coroutines.flow.collectLatest
import java.util.*


class SavedViewModel(handle: SavedStateHandle) : ViewModel() {
    val saved = Pager(PagingConfig(20)) { Db.tags.pagingTags() }.flow
}

class SavedViewModelFactory(owner: SavedStateRegistryOwner, defaultArgs: Bundle?) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = SavedViewModel(handle) as T
}

class SavedActivity : AppCompatActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? SavedFragment ?: SavedFragment()
        val saved = supportFragmentManager.findFragmentById(R.id.container) as? SavedFragment ?: SavedFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .replace(R.id.saved, saved).commit()
    }
}

class SavedFragment : Fragment() {
    private val viewModel: SavedViewModel by viewModels { SavedViewModelFactory(this, null) }
    private val savedAdapter by lazy { SavedAdapter() }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentSavedBinding.inflate(inflater, container, false).also { binding ->
            binding.recycler.adapter = savedAdapter
            lifecycleScope.launchWhenCreated {
                viewModel.saved.collectLatest { savedAdapter.submitData(it) }
            }
            ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
                override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false
                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    lifecycleScope.launchWhenCreated {
                        (viewHolder as? SavedHolder)?.tag?.let {
                            Db.db.tags().deleteTag(it)
                        }
                    }
                }
            }).attachToRecyclerView(binding.recycler)
            binding.button1.setOnClickListener {
                startActivity(Intent(requireContext(), QueryActivity::class.java))
            }
        }.root

    class SavedHolder(val binding: QueryTagItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setCardBackgroundColor(randomColor())
        }

        var tag: DbTag? = null
        fun bind(tag: DbTag) {
            this.tag = tag
            binding.text1.text = tag.name
            binding.text2.text = tag.tag
            binding.swipe.isChecked = tag.pin
        }
    }

    inner class SavedAdapter : PagingDataAdapter<DbTag, SavedHolder>(diffCallback { old, new -> old.id == new.id }) {
        override fun onBindViewHolder(holder: SavedHolder, position: Int) {
            holder.bind(getItem(position)!!)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SavedHolder =
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
                binding.swipe.setOnCheckedChangeListener { _, checked ->
                    val item = getItem(bindingAdapterPosition) ?: return@setOnCheckedChangeListener
                    if (item.pin == checked) return@setOnCheckedChangeListener
                    lifecycleScope.launchWhenCreated {
                        item.pin = checked
                        item.create = Date()
                        Db.tags.updateTag(item)
                    }
                }
            }
    }
}
