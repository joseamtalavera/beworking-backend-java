package com.beworking.invoices;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Single source-of-truth for the admin Overview tab metrics.
 *
 * <p>All numbers come from {@code beworking.facturas} via three small SQL
 * aggregations so the frontend never reduces invoice arrays itself.
 *
 * <p>Conventions (mirrored from Overview.jsx + DailyReconciliationScheduler):
 * <ul>
 *   <li>Revenue includes everything EXCEPT estado matching cancel/void/anula.
 *       A 'Rectificado' original stays counted — its 'Rectificativa' credit
 *       (negative total) nets it back out.</li>
 *   <li>Pendiente buckets are subscription-only: category IN
 *       ('virtual_office','coworking') AND estado matches
 *       pend/confir/fact/invoice/created.</li>
 *   <li>Overdue = estado matches venc/overdue.</li>
 *   <li>YoY revenue uses "same point last year" (same day-of-year).</li>
 *   <li>Pendiente/Overdue are CURRENT-STATE snapshots — no YoY comparison
 *       (year-old pending is "uncollected debt," not a comparable baseline).</li>
 * </ul>
 */
@Service
public class OverviewMetricsService {

    private static final String CANCEL_PATTERN  = "(cancel|void|anula)";
    private static final String PENDING_PATTERN = "(pend|confir|fact|invoice|created)";
    private static final String OVERDUE_PATTERN = "(venc|overdue)";

    private final JdbcTemplate jdbc;

    public OverviewMetricsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> getMetrics(int year) {
        LocalDate today = LocalDate.now();
        int month = today.getMonthValue();
        // YoY-style comparison: this month vs SAME month last year (e.g. May 2026 vs May 2025).
        // Previously compared to the previous month of the same year, which mixed
        // seasonality with YoY growth and hid real trends.
        int prevMonth = month;
        int prevMonthYear = year - 1;
        LocalDate samePointLastYear = today.minusYears(1);

        Map<String, Object> headline = jdbc.queryForMap("""
            SELECT
              COALESCE(SUM(CASE
                WHEN EXTRACT(YEAR FROM creacionfecha) = ?
                 AND LOWER(COALESCE(estado,'')) !~ ?
                THEN total ELSE 0 END), 0)                                   AS income_ytd,
              COALESCE(SUM(CASE
                WHEN EXTRACT(YEAR FROM creacionfecha) = ?
                 AND creacionfecha::date <= ?
                 AND LOWER(COALESCE(estado,'')) !~ ?
                THEN total ELSE 0 END), 0)                                   AS income_last_ytd,
              COALESCE(SUM(CASE
                WHEN EXTRACT(YEAR FROM creacionfecha) = ?
                 AND EXTRACT(MONTH FROM creacionfecha) = ?
                 AND LOWER(COALESCE(estado,'')) !~ ?
                THEN total ELSE 0 END), 0)                                   AS income_month,
              COALESCE(SUM(CASE
                WHEN EXTRACT(YEAR FROM creacionfecha) = ?
                 AND EXTRACT(MONTH FROM creacionfecha) = ?
                 AND LOWER(COALESCE(estado,'')) !~ ?
                THEN total ELSE 0 END), 0)                                   AS income_prev_month,
              COALESCE(SUM(CASE
                WHEN EXTRACT(YEAR FROM creacionfecha) = ?
                 AND LOWER(COALESCE(category,'')) IN ('virtual_office','coworking')
                 AND LOWER(COALESCE(estado,''))   ~ ?
                THEN total ELSE 0 END), 0)                                   AS pending_ytd,
              COALESCE(SUM(CASE
                WHEN EXTRACT(YEAR FROM creacionfecha) = ?
                 AND EXTRACT(MONTH FROM creacionfecha) = ?
                 AND LOWER(COALESCE(category,'')) IN ('virtual_office','coworking')
                 AND LOWER(COALESCE(estado,''))   ~ ?
                THEN total ELSE 0 END), 0)                                   AS pending_month,
              COALESCE(SUM(CASE WHEN LOWER(COALESCE(estado,'')) ~ ? THEN total ELSE 0 END), 0)
                                                                              AS overdue_total,
              COUNT(*) FILTER (WHERE LOWER(COALESCE(estado,'')) ~ ?)         AS overdue_count,
              COUNT(*) FILTER (WHERE EXTRACT(YEAR FROM creacionfecha) = ?)   AS total_invoices
            FROM beworking.facturas
            """,
            year, CANCEL_PATTERN,
            year - 1, java.sql.Date.valueOf(samePointLastYear), CANCEL_PATTERN,
            year, month, CANCEL_PATTERN,
            prevMonthYear, prevMonth, CANCEL_PATTERN,
            year, PENDING_PATTERN,
            year, month, PENDING_PATTERN,
            OVERDUE_PATTERN,
            OVERDUE_PATTERN,
            year
        );

        // The 5 revenue cards group by CUSTOMER tenant_type, NOT facturas.category
        // (the category column is dormant — see project_invoice_category.md).
        // Bucketing matches Overview.jsx#bucketOf:
        //   aulas → meeting_room
        //   mesa/nóma/noma → coworking
        //   virtual → virtual_office
        //   portal/servicio → app
        //   everything else (proveedor, distribuidor, free, NULL) → extra
        List<Map<String, Object>> categoryRows = jdbc.queryForList("""
            WITH classified AS (
              SELECT
                f.creacionfecha,
                f.total,
                f.estado,
                CASE
                  WHEN LOWER(COALESCE(c.tenant_type,'')) LIKE '%aula%'    THEN 'meeting_room'
                  WHEN LOWER(COALESCE(c.tenant_type,'')) LIKE '%mesa%'    THEN 'coworking'
                  WHEN LOWER(COALESCE(c.tenant_type,'')) LIKE '%nóma%'    THEN 'coworking'
                  WHEN LOWER(COALESCE(c.tenant_type,'')) LIKE '%noma%'    THEN 'coworking'
                  WHEN LOWER(COALESCE(c.tenant_type,'')) LIKE '%virtual%' THEN 'virtual_office'
                  WHEN LOWER(COALESCE(c.tenant_type,'')) LIKE '%portal%'  THEN 'app'
                  WHEN LOWER(COALESCE(c.tenant_type,'')) LIKE '%servicio%' THEN 'app'
                  ELSE 'extra'
                END AS bucket
              FROM beworking.facturas f
              LEFT JOIN beworking.contact_profiles c ON c.id = f.idcliente
            )
            SELECT
              bucket AS category,
              COALESCE(SUM(CASE WHEN EXTRACT(YEAR FROM creacionfecha) = ?
                                 AND LOWER(COALESCE(estado,'')) !~ ?
                                THEN total ELSE 0 END), 0)         AS ytd,
              COALESCE(SUM(CASE WHEN EXTRACT(YEAR FROM creacionfecha) = ?
                                 AND EXTRACT(MONTH FROM creacionfecha) = ?
                                 AND LOWER(COALESCE(estado,'')) !~ ?
                                THEN total ELSE 0 END), 0)         AS mtd,
              COALESCE(SUM(CASE WHEN EXTRACT(YEAR FROM creacionfecha) = ?
                                 AND EXTRACT(MONTH FROM creacionfecha) = ?
                                 AND LOWER(COALESCE(estado,'')) !~ ?
                                THEN total ELSE 0 END), 0)         AS prev_month
            FROM classified
            GROUP BY 1
            ORDER BY 1
            """,
            year, CANCEL_PATTERN,
            year, month, CANCEL_PATTERN,
            prevMonthYear, prevMonth, CANCEL_PATTERN
        );

        // Pull both the selected year AND the prior year in one query so the
        // chart can overlay a last-year comparison line.
        List<Map<String, Object>> monthRows = jdbc.queryForList("""
            SELECT
              EXTRACT(YEAR  FROM creacionfecha)::int                          AS yr,
              EXTRACT(MONTH FROM creacionfecha)::int                          AS month,
              COALESCE(SUM(CASE WHEN LOWER(COALESCE(estado,'')) !~ ?
                                 THEN total ELSE 0 END), 0)                  AS revenue,
              COALESCE(SUM(CASE WHEN LOWER(COALESCE(category,'')) IN ('virtual_office','coworking')
                                  AND LOWER(COALESCE(estado,''))   ~ ?
                                 THEN total ELSE 0 END), 0)                  AS pending,
              COALESCE(SUM(CASE WHEN LOWER(COALESCE(estado,'')) ~ ?
                                 THEN total ELSE 0 END), 0)                  AS overdue
            FROM beworking.facturas
            WHERE EXTRACT(YEAR FROM creacionfecha) IN (?, ?)
            GROUP BY 1, 2
            ORDER BY 1, 2
            """,
            CANCEL_PATTERN, PENDING_PATTERN, OVERDUE_PATTERN, year, year - 1
        );

        List<Map<String, Object>> byMonth = blankYear();
        List<Map<String, Object>> byMonthLastYear = blankYear();
        for (Map<String, Object> row : monthRows) {
            int yr = ((Number) row.get("yr")).intValue();
            int m = ((Number) row.get("month")).intValue();
            if (m < 1 || m > 12) continue;
            Map<String, Object> cell = (yr == year ? byMonth : byMonthLastYear).get(m - 1);
            cell.put("revenue", row.get("revenue"));
            cell.put("pending", row.get("pending"));
            cell.put("overdue", row.get("overdue"));
        }

        Map<String, Object> revenue = Map.of(
            "ytd",       headline.get("income_ytd"),
            "lastYtd",   headline.get("income_last_ytd"),
            "month",     headline.get("income_month"),
            "prevMonth", headline.get("income_prev_month")
        );
        Map<String, Object> pending = Map.of(
            "ytd",   headline.get("pending_ytd"),
            "month", headline.get("pending_month")
        );
        Map<String, Object> overdue = Map.of(
            "total", headline.get("overdue_total"),
            "count", headline.get("overdue_count")
        );

        Map<String, Object> out = new HashMap<>();
        out.put("year", year);
        out.put("month", month);
        out.put("totalInvoices", headline.get("total_invoices"));
        out.put("revenue", revenue);
        out.put("pending", pending);
        out.put("overdue", overdue);
        out.put("byCategory", categoryRows);
        out.put("byMonth", byMonth);
        out.put("byMonthLastYear", byMonthLastYear);
        return out;
    }

    /** 12 zero-filled month cells (1-indexed by position). */
    private static List<Map<String, Object>> blankYear() {
        List<Map<String, Object>> months = new ArrayList<>(12);
        for (int m = 1; m <= 12; m++) {
            Map<String, Object> cell = new HashMap<>();
            cell.put("month", m);
            cell.put("revenue", BigDecimal.ZERO);
            cell.put("pending", BigDecimal.ZERO);
            cell.put("overdue", BigDecimal.ZERO);
            months.add(cell);
        }
        return months;
    }
}
