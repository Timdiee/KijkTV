package nl.kijktv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

data class GroupItem(val key: String, val label: String, val count: Int)

class GroupAdapter(
    private val onFocus: (GroupItem) -> Unit,
    private val onClick: (GroupItem) -> Unit
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    var items: List<GroupItem> = emptyList()
        private set
    private var activeKey: String? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val count: TextView = v.findViewById(R.id.count)
    }

    fun submit(list: List<GroupItem>) {
        val sameShape = list.size == items.size && list.indices.all { list[it].key == items[it].key }
        val old = items
        items = list
        if (sameShape) {
            // Alleen aantallen veranderd (bijv. favorieten): niet de focus verstoren.
            for (i in list.indices) if (list[i] != old[i]) notifyItemChanged(i)
        } else {
            notifyDataSetChanged()
        }
    }

    fun setActive(key: String) {
        if (key == activeKey) return
        val old = indexOf(activeKey ?: "")
        activeKey = key
        val now = indexOf(key)
        if (old >= 0) notifyItemChanged(old)
        if (now >= 0) notifyItemChanged(now)
    }

    fun indexOf(key: String) = items.indexOfFirst { it.key == key }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val g = items[position]
        h.name.text = g.label
        h.count.text = g.count.toString()
        h.itemView.isActivated = g.key == activeKey
        h.itemView.setOnFocusChangeListener { _, has -> if (has) onFocus(g) }
        h.itemView.setOnClickListener { onClick(g) }
    }
}

class ChannelAdapter(
    private val onFocus: (Int, Channel) -> Unit,
    private val onClick: (Int, Channel) -> Unit,
    private val onLongClick: (Int, Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var items: List<Channel> = emptyList()
        private set
    private var favorites: Set<String> = emptySet()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.number)
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val star: TextView = v.findViewById(R.id.star)
        val nowLine: TextView = v.findViewById(R.id.nowLine)
        val archive: TextView = v.findViewById(R.id.archive)
    }

    fun submit(list: List<Channel>, favs: Set<String>) {
        items = list
        favorites = favs
        notifyDataSetChanged()
    }

    fun updateFavorites(favs: Set<String>) {
        favorites = favs
        refreshAll()
    }

    /** Alles opnieuw tekenen zonder de focus kwijt te raken (bijv. als de gids geladen is). */
    fun refreshAll() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    fun indexOfUrl(url: String?) = if (url == null) -1 else items.indexOfFirst { it.url == url }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.number.text = (position + 1).toString()
        h.name.text = c.name
        h.star.visibility = if (favorites.contains(c.url)) View.VISIBLE else View.GONE
        h.archive.visibility = if (c.archiveDays > 0) View.VISIBLE else View.GONE
        val now = Epg.now(c)
        if (now != null) {
            h.nowLine.text = "${Fmt.time(now.start)}  ${now.title}"
            h.nowLine.visibility = View.VISIBLE
        } else {
            h.nowLine.visibility = View.GONE
        }
        if (c.logo != null) {
            h.logo.load(c.logo) { crossfade(false) }
        } else {
            h.logo.dispose()
            h.logo.setImageDrawable(null)
        }
        h.itemView.setOnFocusChangeListener { _, has ->
            if (has) {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onFocus(pos, items[pos])
            }
        }
        h.itemView.setOnClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos, items[pos])
        }
        h.itemView.setOnLongClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onLongClick(pos, items[pos])
            true
        }
    }
}

class ProgrammeAdapter(
    private val onFocus: (Programme) -> Unit,
    private val onClick: (Programme) -> Unit
) : RecyclerView.Adapter<ProgrammeAdapter.VH>() {

    var items: List<Programme> = emptyList()
        private set
    private var channel: Channel? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val day: TextView = v.findViewById(R.id.day)
        val row: View = v.findViewById(R.id.row)
        val time: TextView = v.findViewById(R.id.time)
        val title: TextView = v.findViewById(R.id.title)
        val desc: TextView = v.findViewById(R.id.desc)
        val progress: android.widget.ProgressBar = v.findViewById(R.id.progress)
        val badge: TextView = v.findViewById(R.id.badge)
    }

    fun submit(c: Channel?, list: List<Programme>) {
        channel = c
        items = list
        notifyDataSetChanged()
    }

    fun refreshAll() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    /** Positie van het programma dat nu loopt (of het eerstvolgende). */
    fun currentIndex(): Int {
        val t = System.currentTimeMillis()
        val i = items.indexOfFirst { it.stop > t }
        return if (i < 0) (items.size - 1).coerceAtLeast(0) else i
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_programme, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = items[position]
        val t = System.currentTimeMillis()
        val isNow = p.start <= t && p.stop > t
        val isPast = p.stop <= t

        val newDay = position == 0 || !sameDay(items[position - 1].start, p.start)
        if (newDay) {
            h.day.text = Fmt.day(p.start)
            h.day.visibility = View.VISIBLE
        } else {
            h.day.visibility = View.GONE
        }

        h.time.text = Fmt.range(p)
        h.title.text = p.title.ifEmpty { "(geen titel)" }
        val alpha = if (isPast) 0.55f else 1f
        h.title.alpha = alpha
        h.time.alpha = alpha
        h.desc.text = p.desc
        h.desc.visibility = if (p.desc.isNotEmpty() && (isNow || h.row.isFocused)) View.VISIBLE else View.GONE
        if (isNow) {
            h.progress.progress = Fmt.progress(p, t)
            h.progress.visibility = View.VISIBLE
        } else {
            h.progress.visibility = View.GONE
        }
        val c = channel
        val catchup = c != null && Xtream.canCatchup(c, p, t)
        h.badge.text = when {
            isNow && catchup -> "NU  ·  ⟲ vanaf begin"
            isNow -> "NU"
            catchup -> "⟲ terugkijken"
            else -> ""
        }
        h.row.setOnFocusChangeListener { _, has ->
            h.desc.visibility = if (p.desc.isNotEmpty() && (has || isNow)) View.VISIBLE else View.GONE
            if (has) onFocus(p)
        }
        h.row.setOnClickListener { onClick(p) }
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
        val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
        return Fmt.dayNumber(ca) == Fmt.dayNumber(cb)
    }
}
