/* KijkTV voor LG webOS - bouwstenen: lange lijst, kaartenrij en posterraster.
 * Elk onderdeel is één focus-element dat intern de selectie bijhoudt en alleen de zichtbare
 * regels tekent, zodat ook lijsten met duizenden zenders snel blijven. */
'use strict';

(function () {
    var K = KTV.KEY;

    /* ---------------- Verticale lijst ---------------- */

    KTV.VList = function (opts) {
        var self = this;
        this.opts = opts;
        this.items = opts.items || [];
        this.index = 0;
        this.rowH = opts.rowH || 64;
        this.scroll = 0;
        this.root = KTV.el('div', 'vlist f ' + (opts.cls || ''));
        this.inner = KTV.el('div', 'vlist-inner');
        this.emptyEl = KTV.el('div', 'vlist-empty', opts.empty || '');
        this.root.appendChild(this.inner);
        this.root.appendChild(this.emptyEl);
        this.rows = {};
        this.root._noScroll = true;
        this.root._key = function (e) { return self.key(e); };
        this.root._act = function () { if (self.items.length && opts.onAct) opts.onAct(self.items[self.index], self.index); };
        this.root._focus = function () { self.render(); if (opts.onFocus) opts.onFocus(); };
        this.root._blur = function () { self.render(); };
        this.root.addEventListener('wheel', function (e) {
            self.move(e.deltaY > 0 ? 3 : -3, true);
            e.preventDefault();
        });
    };

    KTV.VList.prototype.height = function () { return this.root.clientHeight || 600; };

    KTV.VList.prototype.setItems = function (items, index) {
        this.items = items || [];
        this.index = Math.max(0, Math.min(index || 0, this.items.length - 1));
        this.scroll = 0;
        this.ensureVisible();
        this.inner.innerHTML = '';
        this.rows = {};
        this.render();
        this.emptyEl.style.display = this.items.length ? 'none' : 'flex';
        if (this.items.length && this.opts.onSelect) this.opts.onSelect(this.items[this.index], this.index);
    };

    KTV.VList.prototype.setEmpty = function (t) { this.emptyEl.textContent = t; };

    KTV.VList.prototype.ensureVisible = function () {
        var h = this.height();
        var top = this.index * this.rowH;
        var margin = this.rowH * 2;
        if (top - margin < this.scroll) this.scroll = Math.max(0, top - margin);
        if (top + this.rowH + margin > this.scroll + h) this.scroll = top + this.rowH + margin - h;
        var max = Math.max(0, this.items.length * this.rowH - h);
        this.scroll = Math.max(0, Math.min(this.scroll, max));
    };

    KTV.VList.prototype.render = function () {
        var self = this;
        var h = this.height();
        this.inner.style.height = (this.items.length * this.rowH) + 'px';
        this.inner.style.transform = 'translateY(' + (-this.scroll) + 'px)';
        var first = Math.max(0, Math.floor(this.scroll / this.rowH) - 2);
        var last = Math.min(this.items.length - 1, Math.ceil((this.scroll + h) / this.rowH) + 2);
        var keep = {};
        for (var i = first; i <= last; i++) {
            keep[i] = 1;
            var row = this.rows[i];
            if (!row) {
                row = KTV.el('div', 'vrow');
                row.style.top = (i * this.rowH) + 'px';
                row.style.height = this.rowH + 'px';
                (function (idx, r) {
                    r.addEventListener('mousemove', function () {
                        if (self.index !== idx) { self.index = idx; self.render(); if (self.opts.onSelect) self.opts.onSelect(self.items[idx], idx); }
                    });
                    r.addEventListener('click', function () {
                        self.index = idx; self.render();
                        if (self.opts.onAct) self.opts.onAct(self.items[idx], idx);
                    });
                })(i, row);
                this.opts.render(this.items[i], i, row);
                this.inner.appendChild(row);
                this.rows[i] = row;
            }
            row.classList.toggle('sel', i === this.index);
        }
        Object.keys(this.rows).forEach(function (k) {
            if (!keep[k]) { self.inner.removeChild(self.rows[k]); delete self.rows[k]; }
        });
    };

    /** Tekent de zichtbare regels opnieuw (bijv. als de gids binnenkomt). */
    KTV.VList.prototype.refresh = function () {
        var self = this;
        Object.keys(this.rows).forEach(function (k) {
            var row = self.rows[k];
            row.innerHTML = '';
            self.opts.render(self.items[k], +k, row);
        });
        this.render();
    };

    KTV.VList.prototype.move = function (d, wheel) {
        if (!this.items.length) return false;
        var n = this.index + d;
        if (n < 0 || n >= this.items.length) {
            if (wheel) n = Math.max(0, Math.min(n, this.items.length - 1));
            else return false;
        }
        this.index = n;
        this.ensureVisible();
        this.render();
        if (this.opts.onSelect) this.opts.onSelect(this.items[n], n);
        return true;
    };

    KTV.VList.prototype.key = function (e) {
        var k = e.keyCode;
        if (k === K.UP) return this.move(-1);
        if (k === K.DOWN) return this.move(1);
        if (k === K.CH_UP) { this.move(-Math.min(8, this.index)); return true; }
        if (k === K.CH_DOWN) { this.move(Math.min(8, this.items.length - 1 - this.index)); return true; }
        if (this.opts.onKey) return this.opts.onKey(e, this.items[this.index], this.index);
        return false;
    };

    KTV.VList.prototype.current = function () { return this.items[this.index]; };

    /* ---------------- Kaartenrij (horizontaal) ---------------- */

    KTV.HRow = function (opts) {
        var self = this;
        this.opts = opts;
        this.cards = opts.cards;
        this.index = 0;
        this.offset = 0;
        this.root = KTV.el('div', 'hrow');
        this.root.appendChild(KTV.el('div', 'hrow-title', opts.title));
        this.viewport = KTV.el('div', 'hrow-viewport f');
        this.track = KTV.el('div', 'hrow-track');
        this.viewport.appendChild(this.track);
        this.root.appendChild(this.viewport);
        this.els = [];
        this.cards.forEach(function (c, i) {
            var el = opts.renderCard(c, i);
            el.addEventListener('mousemove', function () { if (self.index !== i) self.select(i); });
            el.addEventListener('click', function () { self.select(i); if (c.act) c.act(); });
            self.track.appendChild(el);
            self.els.push(el);
        });
        var v = this.viewport;
        v._key = function (e) {
            if (e.keyCode === K.LEFT) { if (self.index > 0) { self.select(self.index - 1); return true; } return false; }
            if (e.keyCode === K.RIGHT) { if (self.index < self.cards.length - 1) { self.select(self.index + 1); return true; } return true; }
            return false;
        };
        v._act = function () { var c = self.cards[self.index]; if (c && c.act) c.act(); };
        v._noScroll = true;
        v._focus = function () {
            self.select(self.index);
            // Rij netjes bovenaan het rijen-gebied zetten (zoals bij streamingdiensten).
            var box = self.root.parentNode;
            if (box) box.scrollTop = Math.max(0, self.root.offsetTop - 10);
        };
        v._blur = function () { self.els.forEach(function (e) { e.classList.remove('sel'); }); };
    };

    KTV.HRow.prototype.select = function (i) {
        this.index = i;
        this.els.forEach(function (e, j) { e.classList.toggle('sel', j === i); });
        var el = this.els[i];
        if (!el) return;
        var vw = this.viewport.clientWidth;
        var left = el.offsetLeft, right = left + el.offsetWidth;
        if (left - 40 < this.offset) this.offset = Math.max(0, left - 40);
        if (right + 80 > this.offset + vw) this.offset = right + 80 - vw;
        this.track.style.transform = 'translateX(' + (-this.offset) + 'px)';
        if (this.opts.onSelect) this.opts.onSelect(this.cards[i]);
    };

    /* ---------------- Posterraster ---------------- */

    KTV.VGrid = function (opts) {
        var self = this;
        this.opts = opts;
        this.cols = opts.cols || 6;
        this.cellH = opts.cellH || 380;
        this.items = [];
        this.index = 0;
        this.scroll = 0;
        this.root = KTV.el('div', 'vgrid f');
        this.inner = KTV.el('div', 'vgrid-inner');
        this.emptyEl = KTV.el('div', 'vlist-empty', opts.empty || '');
        this.root.appendChild(this.inner);
        this.root.appendChild(this.emptyEl);
        this.cells = {};
        this.root._noScroll = true;
        this.root._key = function (e) { return self.key(e); };
        this.root._act = function () { if (self.items.length && opts.onAct) opts.onAct(self.items[self.index], self.index); };
        this.root._focus = function () { self.render(); };
        this.root._blur = function () { self.render(); };
        this.root.addEventListener('wheel', function (e) { self.move(e.deltaY > 0 ? self.cols : -self.cols, true); e.preventDefault(); });
    };

    KTV.VGrid.prototype.setItems = function (items, index) {
        this.items = items || [];
        this.index = Math.max(0, Math.min(index || 0, this.items.length - 1));
        this.scroll = 0;
        this.inner.innerHTML = '';
        this.cells = {};
        this.ensureVisible();
        this.render();
        this.emptyEl.style.display = this.items.length ? 'none' : 'flex';
    };

    KTV.VGrid.prototype.setEmpty = function (t) { this.emptyEl.textContent = t; };

    KTV.VGrid.prototype.ensureVisible = function () {
        var h = this.root.clientHeight || 800;
        var row = Math.floor(this.index / this.cols);
        var top = row * this.cellH;
        if (top < this.scroll) this.scroll = top;
        if (top + this.cellH > this.scroll + h) this.scroll = top + this.cellH - h;
        this.scroll = Math.max(0, this.scroll);
    };

    KTV.VGrid.prototype.render = function () {
        var self = this;
        var h = this.root.clientHeight || 800;
        var w = this.root.clientWidth || 1400;
        var cellW = w / this.cols;
        var rowsTotal = Math.ceil(this.items.length / this.cols);
        this.inner.style.height = (rowsTotal * this.cellH) + 'px';
        this.inner.style.transform = 'translateY(' + (-this.scroll) + 'px)';
        var firstRow = Math.max(0, Math.floor(this.scroll / this.cellH) - 1);
        var lastRow = Math.min(rowsTotal - 1, Math.ceil((this.scroll + h) / this.cellH) + 1);
        var keep = {};
        for (var r = firstRow; r <= lastRow; r++) {
            for (var c = 0; c < this.cols; c++) {
                var i = r * this.cols + c;
                if (i >= this.items.length) break;
                keep[i] = 1;
                var cell = this.cells[i];
                if (!cell) {
                    cell = this.opts.render(this.items[i], i);
                    cell.style.position = 'absolute';
                    cell.style.left = (c * cellW) + 'px';
                    cell.style.top = (r * this.cellH) + 'px';
                    cell.style.width = cellW + 'px';
                    (function (idx, el) {
                        el.addEventListener('mousemove', function () { if (self.index !== idx) { self.index = idx; self.render(); } });
                        el.addEventListener('click', function () { self.index = idx; if (self.opts.onAct) self.opts.onAct(self.items[idx], idx); });
                    })(i, cell);
                    this.inner.appendChild(cell);
                    this.cells[i] = cell;
                }
                cell.classList.toggle('sel', i === this.index && this.root.classList.contains('focused'));
            }
        }
        Object.keys(this.cells).forEach(function (k) {
            if (!keep[k]) { self.inner.removeChild(self.cells[k]); delete self.cells[k]; }
        });
    };

    KTV.VGrid.prototype.move = function (d, wheel) {
        var n = this.index + d;
        if (n < 0 || n >= this.items.length) {
            if (!wheel) return false;
            n = Math.max(0, Math.min(n, this.items.length - 1));
        }
        this.index = n;
        this.ensureVisible();
        this.render();
        return true;
    };

    KTV.VGrid.prototype.key = function (e) {
        var k = e.keyCode, col = this.index % this.cols;
        if (k === K.LEFT) return col > 0 ? this.move(-1) : false;
        if (k === K.RIGHT) { if (col < this.cols - 1 && this.index < this.items.length - 1) this.move(1); return true; }
        if (k === K.UP) return this.index >= this.cols ? this.move(-this.cols) : false;
        if (k === K.DOWN) {
            if (this.index + this.cols < this.items.length) return this.move(this.cols);
            var lastRowStart = Math.floor((this.items.length - 1) / this.cols) * this.cols;
            if (this.index < lastRowStart) return this.move(this.items.length - 1 - this.index);
            return true;
        }
        return false;
    };

    /* ---------------- Afbeelding met terugval ---------------- */

    KTV.img = function (src, cls) {
        var i = KTV.el('img', cls || '');
        if (src) {
            i.src = src;
            i.onerror = function () { i.style.visibility = 'hidden'; };
        } else {
            i.style.visibility = 'hidden';
        }
        return i;
    };
})();
