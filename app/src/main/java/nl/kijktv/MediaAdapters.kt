package nl.kijktv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

class PosterItem(val name: String, val image: String?, val progress: Int)

class PosterAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<PosterAdapter.VH>() {

    var items: List<PosterItem> = emptyList()
        private set

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.poster)
        val placeholder: TextView = v.findViewById(R.id.placeholder)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val name: TextView = v.findViewById(R.id.name)
    }

    fun submit(list: List<PosterItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = items[position]
        h.name.text = p.name
        if (p.image != null) {
            h.placeholder.text = ""
            h.poster.load(p.image) { crossfade(false) }
        } else {
            h.poster.dispose()
            h.poster.setImageDrawable(null)
            h.placeholder.text = p.name
        }
        if (p.progress > 0) {
            h.progress.progress = p.progress
            h.progress.visibility = View.VISIBLE
        } else {
            h.progress.visibility = View.GONE
        }
        h.itemView.setOnClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
    }
}

class EpisodeAdapter(
    private val progressOf: (Episode) -> WatchItem?,
    private val onClick: (Episode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    var items: List<Episode> = emptyList()
        private set

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.number)
        val title: TextView = v.findViewById(R.id.title)
        val desc: TextView = v.findViewById(R.id.desc)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val badge: TextView = v.findViewById(R.id.badge)
    }

    fun submit(list: List<Episode>) {
        items = list
        notifyDataSetChanged()
    }

    fun refreshAll() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.number.text = e.number.toString()
        h.title.text = e.title
        h.desc.text = e.plot
        h.desc.visibility = if (e.plot.isEmpty()) View.GONE else View.VISIBLE
        val w = progressOf(e)
        when {
            w != null && w.finished -> {
                h.badge.text = "Gezien"
                h.progress.visibility = View.GONE
            }
            w != null && w.resumable -> {
                h.badge.text = History.clock(w.positionMs)
                h.progress.progress = w.progress
                h.progress.visibility = View.VISIBLE
            }
            else -> {
                h.badge.text = if (e.durationSec > 0) "${e.durationSec / 60} min" else ""
                h.progress.visibility = View.GONE
            }
        }
        h.itemView.setOnClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
        }
    }
}

class HistoryAdapter(
    private val onClick: (WatchItem) -> Unit,
    private val onLongClick: (WatchItem) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    var items: List<WatchItem> = emptyList()
        private set

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.image)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val time: TextView = v.findViewById(R.id.whenText)
    }

    fun submit(list: List<WatchItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val w = items[position]
        val icon = when (w.kind) {
            WatchItem.LIVE -> "Live tv  ·  "
            WatchItem.MOVIE -> "Film  ·  "
            WatchItem.EPISODE -> "Serie  ·  "
            WatchItem.CATCHUP -> "Terugkijken  ·  "
            else -> ""
        }
        h.title.text = w.title
        val state = when {
            w.finished -> "  ·  uitgekeken"
            w.resumable -> "  ·  gestopt bij ${History.clock(w.positionMs)}"
            else -> ""
        }
        h.sub.text = icon + w.subtitle + state
        if (w.resumable) {
            h.progress.progress = w.progress
            h.progress.visibility = View.VISIBLE
        } else {
            h.progress.visibility = View.GONE
        }
        h.time.text = "${Fmt.day(w.updatedAt)}\n${Fmt.time(w.updatedAt)}"
        if (w.image != null) h.image.load(w.image) { crossfade(false) } else { h.image.dispose(); h.image.setImageDrawable(null) }
        h.itemView.setOnClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
        }
        h.itemView.setOnLongClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onLongClick(items[pos])
            true
        }
    }
}
