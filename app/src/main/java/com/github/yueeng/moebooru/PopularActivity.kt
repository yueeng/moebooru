package com.github.yueeng.moebooru

import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.github.yueeng.moebooru.databinding.FragmentPopularBinding
import com.github.yueeng.moebooru.databinding.PopularTabItemBinding
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker
import java.text.SimpleDateFormat
import java.util.*

class PopularActivity : MoeActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.run {
            val fragment = findFragmentById(R.id.container) as? PopularFragment ?: PopularFragment().apply { arguments = intent.extras }
            val mine = findFragmentById(R.id.mine) as? UserFragment ?: UserFragment()
            val saved = findFragmentById(R.id.saved) as? SavedFragment ?: SavedFragment()
            beginTransaction().replace(R.id.container, fragment)
                .replace(R.id.mine, mine)
                .replace(R.id.saved, saved)
                .commit()
        }
    }
}

class PopularViewModel(handle: SavedStateHandle) : ViewModel() {
    val index = handle.getLiveData<Int>("index")
}

class PopularViewModelFactory(owner: SavedStateRegistryOwner, defaultArgs: Bundle?) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = PopularViewModel(handle) as T
}

class PopularFragment : Fragment(), SavedFragment.Queryable {
    private val model: PopularViewModel by viewModels { PopularViewModelFactory(this, arguments) }
    private val type by lazy { arguments?.getString("type") ?: "day" }
    private val adapter by lazy { PopularAdapter(this) }
    private val tabAdapter by lazy { TabAdapter() }
    private lateinit var binding: FragmentPopularBinding
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentPopularBinding.inflate(inflater, container, false).also { binding ->
            this.binding = binding
            (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
            requireActivity().setTitle(
                when (type) {
                    "day" -> R.string.popular_by_day
                    "week" -> R.string.popular_by_week
                    "month" -> R.string.popular_by_month
                    else -> R.string.popular_by_year
                }
            )
            binding.pager.adapter = adapter
            tabAdapter.submitList((1..adapter.itemCount).mapNotNull { adapter.getPageTitle(it - 1) })
            binding.tab.adapter = tabAdapter
            binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    model.index.postValue(position)
                }
            })
            var last = -1
            model.index.observe(viewLifecycleOwner, { position ->
                binding.pager.currentItem = position
                if (last != position && last != -1) tabAdapter.notifyItemChanged(last, "check")
                tabAdapter.notifyItemChanged(position, "check")
                last = position
                binding.tab.scrollToPosition(position)
            })
            binding.button1.setOnClickListener {
                val constraints = CalendarConstraints.Builder()
                    .setStart(moeCreateTime.timeInMillis)
                    .setEnd(calendar().timeInMillis)
                    .setOpenAt(adapter.getTime(binding.pager.currentItem).timeInMillis)
                    .build()
                val picker = MaterialDatePicker.Builder.datePicker()
                    .setSelection(adapter.getTime(binding.pager.currentItem).timeInMillis)
                    .setCalendarConstraints(constraints)
                    .build()
                picker.addOnPositiveButtonClickListener {
                    binding.pager.currentItem = date2pos(calendar().apply { time = Date(it) })
                }
                picker.show(childFragmentManager, "picker")
            }
        }.root

    fun date2pos(target: Calendar) = when (type) {
        "day" -> calendar().minus(target).daysGreedy - 1
        "week" -> calendar().minus(target).weeksGreedy - 1
        "month" -> calendar().minus(target).months - 1
        "year" -> calendar().minus(target).year - 1
        else -> throw  IllegalArgumentException()
    }

    fun pos2date(position: Int) = when (type) {
        "day" -> calendar().day(-position, true)
        "week" -> calendar().weekOfYear(-position, true)
        "month" -> calendar().month(-position, true)
        "year" -> calendar().year(-position, true)
        else -> throw  IllegalArgumentException()
    }

    inner class PopularAdapter(fm: Fragment) : FragmentStateAdapter(fm) {
        override fun getItemCount(): Int = date2pos(moeCreateTime) + 1

        override fun createFragment(position: Int) = ImageFragment().apply {
            arguments = bundleOf("query" to getItem(position))
        }

        fun getItem(position: Int) = Q().popular(type, pos2date(position).time)
        private val dayFormatter get() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply { timeZone = utcTimeZone }
        private val monthFormatter get() = SimpleDateFormat("yyyy-MM", Locale.getDefault()).apply { timeZone = utcTimeZone }
        private val yearFormatter get() = SimpleDateFormat("yyyy", Locale.getDefault()).apply { timeZone = utcTimeZone }
        fun getTime(position: Int) = pos2date(position)
        fun getPageTitle(position: Int): CharSequence = when (type) {
            "day" -> pos2date(position).format(dayFormatter)
            "week" -> pos2date(position).let {
                "${it.lastDayOfWeekWithLocale.format(monthFormatter)} W${it.lastDayOfWeekWithLocale.get(Calendar.WEEK_OF_MONTH)}"
            }
            "month" -> pos2date(position).firstDayOfMonth.format(monthFormatter)
            "year" -> pos2date(position).format(yearFormatter)
            else -> throw IllegalArgumentException()
        }
    }

    inner class TabHolder(private val binding: PopularTabItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                model.index.postValue(bindingAdapterPosition)
            }
        }

        fun bind(i: Int, value: CharSequence, payloads: MutableList<Any>?) {
            binding.check1.visibility = if (i == model.index.value) View.VISIBLE else View.INVISIBLE
            if (payloads?.isEmpty() != false) {
                binding.text1.text = value
            }
        }
    }

    inner class TabAdapter : ListAdapter<CharSequence, TabHolder>(diffCallback { old, new -> old == new }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabHolder =
            TabHolder(PopularTabItemBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: TabHolder, position: Int, payloads: MutableList<Any>) = holder.bind(position, getItem(position), payloads)

        override fun onBindViewHolder(holder: TabHolder, position: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.main, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.column -> true.also {
            MoeSettings.column()
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun query(): Q = adapter.getItem(binding.pager.currentItem)
}