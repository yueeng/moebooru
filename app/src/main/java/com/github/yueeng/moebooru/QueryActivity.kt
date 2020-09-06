package com.github.yueeng.moebooru

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.github.yueeng.moebooru.databinding.FragmentQueryBinding
import com.github.yueeng.moebooru.databinding.QueryItemBinding

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
        }.root

    class QueryHolder(val binding: QueryItemBinding) : RecyclerView.ViewHolder(binding.root)
    inner class QueryAdapter : RecyclerView.Adapter<QueryHolder>() {
        val data = query.map.toList().toMutableList()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueryHolder = QueryHolder(QueryItemBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: QueryHolder, position: Int) {
            val item = data[position]
            holder.binding.text1.text = item.first
            holder.binding.text2.text = "${item.second}"
        }

        override fun getItemCount(): Int = data.size
    }
}