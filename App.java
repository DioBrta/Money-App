import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@SpringBootApplication
@RestController
public class App {

    private static final String DB_URL = "jdbc:sqlite:" + new File("cash_log.db").getAbsolutePath();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<Map<String, String>> CATEGORIES = List.of(
        Map.of("id", "food",       "label", "Food",        "icon", "🍜", "color", "#F0997B"),
        Map.of("id", "transport",  "label", "Transport",   "icon", "🛵", "color", "#85B7EB"),
        Map.of("id", "shopping",   "label", "Shopping",    "icon", "🛍️", "color", "#AFA9EC"),
        Map.of("id", "bills",      "label", "Bills",       "icon", "💡", "color", "#FAC775"),
        Map.of("id", "health",     "label", "Health",      "icon", "💊", "color", "#9FE1CB"),
        Map.of("id", "other",      "label", "Other",       "icon", "📦", "color", "#D3D1C7")
    );

    public static void main(String[] args) {
        initDb();
        SpringApplication.run(App.class, args);
    }

    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    private static void initDb() {
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS transactions (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT,
                        amount    REAL    NOT NULL,
                        category  TEXT    NOT NULL,
                        note      TEXT    DEFAULT '',
                        logged_at TEXT    NOT NULL
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS withdrawals (
                        id           INTEGER PRIMARY KEY AUTOINCREMENT,
                        amount       REAL    NOT NULL,
                        withdrawn_at TEXT    NOT NULL
                    )
                """);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @GetMapping(value = "/", produces = "text/html;charset=UTF-8")
    public String index() throws Exception {
        String catsJson = MAPPER.writeValueAsString(CATEGORIES);
        return HTML.replace("{{ cats|tojson }}", catsJson);
    }

    @PostMapping("/log")
    public ResponseEntity<?> logExpense(@RequestBody Map<String, Object> data) throws SQLException {
        double amount = Double.parseDouble(data.get("amount").toString());
        String category = (String) data.get("category");
        String note = data.getOrDefault("note", "").toString();
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        String sql = "INSERT INTO transactions (amount, category, note, logged_at) VALUES (?,?,?,?)";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, amount);
            pstmt.setString(2, category);
            pstmt.setString(3, note);
            pstmt.setString(4, now);
            pstmt.executeUpdate();
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/withdrawal")
    public ResponseEntity<?> logWithdrawal(@RequestBody Map<String, Object> data) throws SQLException {
        double amount = Double.parseDouble(data.get("amount").toString());
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        String sql = "INSERT INTO withdrawals (amount, withdrawn_at) VALUES (?,?)";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, amount);
            pstmt.setString(2, now);
            pstmt.executeUpdate();
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/stats/today")
    public ResponseEntity<?> statsToday() throws SQLException {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        double total = 0;
        int count = 0;
        List<Map<String, Object>> byCategory = new ArrayList<>();

        String summarySql = "SELECT COALESCE(SUM(amount),0) as total, COUNT(*) as count FROM transactions WHERE logged_at LIKE ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(summarySql)) {
            pstmt.setString(1, today + "%");
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    total = rs.getDouble("total");
                    count = rs.getInt("count");
                }
            }
        }

        String catSql = "SELECT category, SUM(amount) as total FROM transactions WHERE logged_at LIKE ? GROUP BY category ORDER BY total DESC";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(catSql)) {
            pstmt.setString(1, today + "%");
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    byCategory.add(Map.of(
                        "category", rs.getString("category"),
                        "total", rs.getDouble("total")
                    ));
                }
            }
        }

        return ResponseEntity.ok(Map.of(
            "total", total,
            "count", count,
            "by_category", byCategory
        ));
    }

    @GetMapping("/recent")
    public ResponseEntity<?> recent() throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        String sql = "SELECT * FROM transactions ORDER BY logged_at DESC LIMIT 20";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("amount", rs.getDouble("amount"));
                row.put("category", rs.getString("category"));
                row.put("note", rs.getString("note"));
                row.put("logged_at", rs.getString("logged_at"));
                rows.add(row);
            }
        }
        return ResponseEntity.ok(rows);
    }

    @DeleteMapping("/delete/{txId}")
    public ResponseEntity<?> deleteTx(@PathVariable int txId) throws SQLException {
        String sql = "DELETE FROM transactions WHERE id=?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, txId);
            pstmt.executeUpdate();
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/wallet")
    public ResponseEntity<?> wallet() throws SQLException {
        String weekAgo = LocalDateTime.now().minusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        double withdrawn = 0;
        double logged = 0;

        String wSql = "SELECT COALESCE(SUM(amount),0) as total FROM withdrawals WHERE withdrawn_at >= ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(wSql)) {
            pstmt.setString(1, weekAgo);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) withdrawn = rs.getDouble("total");
            }
        }

        String tSql = "SELECT COALESCE(SUM(amount),0) as total FROM transactions WHERE logged_at >= ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(tSql)) {
            pstmt.setString(1, weekAgo);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) logged = rs.getDouble("total");
            }
        }

        return ResponseEntity.ok(Map.of("withdrawn", withdrawn, "logged_cash", logged));
    }

    private static final String HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
        <title>Quick Log</title>
        <style>
          *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
          :root {
            --bg: #f5f4ef; --surface: #ffffff; --border: rgba(0,0,0,0.1);
            --text: #1a1a18; --muted: #6b6a64; --radius: 12px;
            --green: #0F6E56; --green-bg: #E1F5EE;
          }
          body { background: var(--bg); font-family: system-ui, sans-serif; color: var(--text);
                 min-height: 100vh; display: flex; flex-direction: column; align-items: center; padding: 16px; }
          h1 { font-size: 18px; font-weight: 600; margin-bottom: 4px; }
          .subtitle { font-size: 13px; color: var(--muted); margin-bottom: 20px; }
        
          /* --- Quick Entry Card --- */
          .card { background: var(--surface); border: 0.5px solid var(--border); border-radius: var(--radius);
                  padding: 20px; width: 100%; max-width: 400px; margin-bottom: 16px; }
          .card-title { font-size: 13px; font-weight: 600; color: var(--muted); text-transform: uppercase;
                        letter-spacing: .05em; margin-bottom: 14px; }
        
          /* Amount input */
          .amount-row { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
          .currency { font-size: 20px; font-weight: 600; color: var(--muted); }
          #amount { flex: 1; font-size: 32px; font-weight: 600; border: none; outline: none;
                     background: transparent; color: var(--text); width: 100%; }
          #amount::placeholder { color: #ccc; }
          .amount-row { border-bottom: 1.5px solid var(--border); padding-bottom: 10px; }
          .amount-row:focus-within { border-bottom-color: var(--green); }
        
          /* Category grid */
          .cat-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-bottom: 16px; }
          .cat-btn { border: 0.5px solid var(--border); border-radius: 10px; padding: 10px 6px;
                     background: var(--surface); cursor: pointer; text-align: center;
                     transition: all .15s; display: flex; flex-direction: column; align-items: center; gap: 4px; }
          .cat-btn .icon { font-size: 22px; }
          .cat-btn .cat-label { font-size: 11px; color: var(--muted); font-weight: 500; }
          .cat-btn.selected { border-width: 2px; }
          .cat-btn:active { transform: scale(0.96); }
        
          /* Note */
          #note { width: 100%; border: 0.5px solid var(--border); border-radius: 8px; padding: 8px 10px;
                  font-size: 14px; color: var(--text); background: var(--bg); outline: none;
                  margin-bottom: 14px; font-family: inherit; resize: none; }
          #note:focus { border-color: var(--green); }
        
          /* Save button */
          #save-btn { width: 100%; padding: 13px; background: var(--green); color: #fff;
                      border: none; border-radius: var(--radius); font-size: 16px; font-weight: 600;
                      cursor: pointer; transition: opacity .15s; }
          #save-btn:active { opacity: .85; transform: scale(0.98); }
          #save-btn:disabled { opacity: .4; cursor: not-allowed; }
        
          /* Toast */
          #toast { position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%) translateY(60px);
                   background: #1a1a18; color: #fff; padding: 10px 20px; border-radius: 20px;
                   font-size: 14px; transition: transform .25s; pointer-events: none; white-space: nowrap; }
          #toast.show { transform: translateX(-50%) translateY(0); }
        
          /* Stats */
          .stats-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 10px; }
          .stat { background: var(--bg); border-radius: 10px; padding: 12px; }
          .stat-label { font-size: 11px; color: var(--muted); margin-bottom: 4px; font-weight: 500; text-transform: uppercase; letter-spacing:.04em; }
          .stat-val { font-size: 20px; font-weight: 600; }
        
          /* Recent list */
          .recent-item { display: flex; align-items: center; justify-content: space-between;
                         padding: 9px 0; border-bottom: 0.5px solid var(--border); }
          .recent-item:last-child { border-bottom: none; }
          .ri-left { display: flex; align-items: center; gap: 10px; }
          .ri-icon { font-size: 20px; }
          .ri-cat { font-size: 13px; font-weight: 500; }
          .ri-note { font-size: 12px; color: var(--muted); }
          .ri-time { font-size: 11px; color: var(--muted); }
          .ri-amount { font-size: 15px; font-weight: 600; }
          .delete-btn { background: none; border: none; cursor: pointer; color: #ccc; font-size: 16px;
                        padding: 2px 6px; border-radius: 4px; }
          .delete-btn:hover { color: #e24b4a; background: #fcebeb; }
        
          /* Withdrawal */
          .withdraw-row { display: flex; gap: 8px; }
          #w-amount { flex:1; border: 0.5px solid var(--border); border-radius: 8px; padding: 8px 10px;
                      font-size: 14px; outline: none; background: var(--bg); color: var(--text); }
          #w-amount:focus { border-color: var(--green); }
          #w-btn { padding: 8px 16px; background: var(--green-bg); color: var(--green); border: 0.5px solid var(--green);
                   border-radius: 8px; font-size: 13px; font-weight: 600; cursor: pointer; }
        
          .untracked { background: #FAEEDA; border-radius: 8px; padding: 10px 12px; margin-top: 10px;
                       font-size: 13px; color: #633806; }
        
          .empty { text-align: center; color: var(--muted); font-size: 13px; padding: 20px 0; }
        
          /* Chart bars */
          .bar-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; font-size: 12px; }
          .bar-label { width: 70px; color: var(--muted); text-align: right; flex-shrink:0; }
          .bar-track { flex:1; height: 8px; background: var(--bg); border-radius: 4px; overflow: hidden; }
          .bar-fill { height: 8px; border-radius: 4px; transition: width .4s; }
          .bar-amt { width: 70px; font-weight: 500; color: var(--text); }
        </style>
        </head>
        <body>
        
        <h1>Quick Log</h1>
        <p class="subtitle">Track your cash spending instantly</p>
        
        <!-- Entry Card -->
        <div class="card">
          <div class="card-title">Log a cash expense</div>
          <div class="amount-row">
            <span class="currency">₫</span>
            <input type="number" id="amount" placeholder="0" min="0" autofocus inputmode="numeric">
          </div>
          <div class="cat-grid" id="cat-grid"></div>
          <input type="text" id="note" placeholder="Note (optional)" maxlength="80">
          <button id="save-btn" disabled>Save expense</button>
        </div>
        
        <!-- Today's Stats -->
        <div class="card">
          <div class="card-title">Today</div>
          <div class="stats-grid">
            <div class="stat"><div class="stat-label">Total spent</div><div class="stat-val" id="today-total">₫0</div></div>
            <div class="stat"><div class="stat-label">Transactions</div><div class="stat-val" id="today-count">0</div></div>
          </div>
          <div id="cat-bars"></div>
        </div>
        
        <!-- Wallet Drain -->
        <div class="card">
          <div class="card-title">Wallet tracker</div>
          <div class="withdraw-row">
            <input type="number" id="w-amount" placeholder="ATM withdrawal amount (₫)" inputmode="numeric">
            <button id="w-btn">+ Log withdrawal</button>
          </div>
          <div id="untracked-box"></div>
        </div>
        
        <!-- Recent -->
        <div class="card">
          <div class="card-title">Recent entries</div>
          <div id="recent-list"><div class="empty">No entries yet</div></div>
        </div>
        
        <div id="toast"></div>
        
        <script>
        const CATS = {{ cats|tojson }};
        let selected = null;
        
        // Build category buttons
        const grid = document.getElementById('cat-grid');
        CATS.forEach(c => {
          const btn = document.createElement('button');
          btn.className = 'cat-btn';
          btn.dataset.id = c.id;
          btn.innerHTML = `<span class="icon">\${c.icon}</span><span class="cat-label">\${c.label}</span>`;
          btn.style.setProperty('--cat-color', c.color);
          btn.onclick = () => {
            document.querySelectorAll('.cat-btn').forEach(b => {
              b.classList.remove('selected');
              b.style.borderColor = '';
              b.style.background = '';
            });
            btn.classList.add('selected');
            btn.style.borderColor = c.color;
            btn.style.background = c.color + '22';
            selected = c.id;
            checkReady();
          };
          grid.appendChild(btn);
        });
        
        const amountEl = document.getElementById('amount');
        const saveBtn  = document.getElementById('save-btn');
        
        amountEl.addEventListener('input', checkReady);
        function checkReady() {
          saveBtn.disabled = !(parseFloat(amountEl.value) > 0 && selected);
        }
        
        function fmt(n) {
          return '₫' + Math.round(n).toLocaleString('vi-VN');
        }
        
        function toast(msg) {
          const t = document.getElementById('toast');
          t.textContent = msg;
          t.classList.add('show');
          setTimeout(() => t.classList.remove('show'), 2000);
        }
        
        saveBtn.onclick = async () => {
          const amount   = parseFloat(amountEl.value);
          const category = selected;
          const note     = document.getElementById('note').value.trim();
          saveBtn.disabled = true;
          const r = await fetch('/log', {
            method: 'POST',
            headers: {'Content-Type':'application/json'},
            body: JSON.stringify({amount, category, note})
          });
          if (r.ok) {
            toast('Saved!');
            amountEl.value = '';
            document.getElementById('note').value = '';
            document.querySelectorAll('.cat-btn').forEach(b => {
              b.classList.remove('selected');
              b.style.borderColor = '';
              b.style.background = '';
            });
            selected = null;
            saveBtn.disabled = true;
            loadStats();
            loadRecent();
            loadWallet();
          }
        };
        
        document.getElementById('w-btn').onclick = async () => {
          const amt = parseFloat(document.getElementById('w-amount').value);
          if (!amt || amt <= 0) { toast('Enter a withdrawal amount'); return; }
          await fetch('/withdrawal', {
            method: 'POST',
            headers: {'Content-Type':'application/json'},
            body: JSON.stringify({amount: amt})
          });
          document.getElementById('w-amount').value = '';
          toast('Withdrawal logged');
          loadWallet();
        };
        
        async function loadStats() {
          const d = await fetch('/stats/today').then(r => r.json());
          document.getElementById('today-total').textContent = fmt(d.total);
          document.getElementById('today-count').textContent = d.count;
          const bars = document.getElementById('cat-bars');
          if (!d.by_category || d.by_category.length === 0) { bars.innerHTML = ''; return; }
          const max = Math.max(...d.by_category.map(c => c.total));
          bars.innerHTML = d.by_category.map(c => {
            const cat = CATS.find(x => x.id === c.category) || {label: c.category, color: '#888', icon: '•'};
            const pct = max > 0 ? (c.total / max * 100) : 0;
            return `<div class="bar-row">
              <div class="bar-label">	extbf{\${cat.icon}} \${cat.label}</div>
              <div class="bar-track"><div class="bar-fill" style="width:\${pct}%;background:\${cat.color}"></div></div>
              <div class="bar-amt">\${fmt(c.total)}</div>
            </div>`;
          }).join('');
        }
        
        async function loadRecent() {
          const data = await fetch('/recent').then(r => r.json());
          const list = document.getElementById('recent-list');
          if (!data.length) { list.innerHTML = '<div class="empty">No entries yet</div>'; return; }
          list.innerHTML = data.map(tx => {
            const cat = CATS.find(c => c.id === tx.category) || {label: tx.category, icon: '📦', color:'#888'};
            const time = tx.logged_at.split('T')[1]?.slice(0,5) || tx.logged_at.slice(11,16);
            return `<div class="recent-item">
              <div class="ri-left">
                <span class="ri-icon">\${cat.icon}</span>
                <div>
                  <div class="ri-cat">\${cat.label}</div>
                  \${tx.note ? `<div class="ri-note">\${tx.note}</div>` : ''}
                  <div class="ri-time">\${time}</div>
                </div>
              </div>
              <div style="display:flex;align-items:center;gap:6px">
                <span class="ri-amount" style="color:\${cat.color}">\${fmt(tx.amount)}</span>
                <button class="delete-btn" onclick="deleteEntry(\${tx.id})">✕</button>
              </div>
            </div>`;
          }).join('');
        }
        
        async function deleteEntry(id) {
          await fetch('/delete/' + id, {method:'DELETE'});
          toast('Deleted');
          loadStats(); loadRecent(); loadWallet();
        }
        
        async function loadWallet() {
          const d = await fetch('/wallet').then(r => r.json());
          const box = document.getElementById('untracked-box');
          if (d.withdrawn === 0) { box.innerHTML = ''; return; }
          const untracked = Math.max(0, d.withdrawn - d.logged_cash);
          box.innerHTML = `<div class="untracked">
            Withdrew \${fmt(d.withdrawn)} · Logged \${fmt(d.logged_cash)} ·
            <strong>~\${fmt(untracked)} untracked</strong> this week
          </div>`;
        }
        
        loadStats(); loadRecent(); loadWallet();
        </script>
        </body>
        </html>
        """;
}
