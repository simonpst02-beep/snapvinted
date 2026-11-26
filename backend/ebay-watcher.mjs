const API = "https://api.ebay.com";

const HS = "7000";
const OK = "1000|1500|2000|2010|2020|2030|2500|3000";

const ACCESS =
  /(coque|housse|[ée]tui|bo[iî]te vide|boite seule|chargeur|c[aâ]ble|verre tremp[ée]|protection|film|support|pi[eè]ces? d[ée]tach[ée]es?|carte m[eè]re|[ée]cran seul|nappe|batterie seule)/i;

const HS_WORDS =
  /(hs|panne|pour pi[eè]ces?|d[ée]fectueu[sx]e?|cass[ée]e?s?|ne fonctionne pas|bloqu[ée]e?s?|ne s'allume pas|en l'[ée]tat|for parts|broken)/giu;

let tokenCache = {
  value: null,
  expires: 0
};

let running = false;


const num = v => {
  if (v === null || v === undefined || v === "") return null;

  const n = Number(v);

  return Number.isFinite(n) ? n : null;
};


function median(values) {
  const a = [...values].sort((a, b) => a - b);

  if (!a.length) return 0;

  const m = Math.floor(a.length / 2);

  return a.length % 2
    ? a[m]
    : (a[m - 1] + a[m]) / 2;
}


function robust(values) {
  const a = values
    .map(Number)
    .filter(v => Number.isFinite(v) && v > 0);

  if (!a.length) {
    return {
      value: null,
      count: 0,
      min: null,
      max: null
    };
  }

  const med = median(a);

  const good = a
    .filter(v => v >= med * 0.5 && v <= med * 2)
    .sort((x, y) => x - y);

  if (!good.length) {
    return {
      value: null,
      count: 0,
      min: null,
      max: null
    };
  }

  return {
    value: median(good),
    count: good.length,
    min: good[0],
    max: good[good.length - 1]
  };
}


function priceOf(item) {
  return num(
    item?.price?.value ??
    item?.currentBidPrice?.value
  ) ?? 0;
}


function shippingOf(item) {
  return num(
    item?.shippingOptions?.[0]?.shippingCost?.value
  ) ?? 0;
}


function cleanTitle(title) {
  return String(title || "")
    .toLowerCase()
    .replace(HS_WORDS, " ")
    .replace(/\blot\s*(de\s*)?\d+\b/gi, " ")
    .replace(/[^\p{L}\p{N}\s]/gu, " ")
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 6)
    .join(" ");
}


async function accessToken() {
  if (
    tokenCache.value &&
    Date.now() < tokenCache.expires - 60000
  ) {
    return tokenCache.value;
  }

  const id = process.env.EBAY_CLIENT_ID;
  const secret = process.env.EBAY_CLIENT_SECRET;

  if (!id || !secret) {
    throw new Error(
      "EBAY_CLIENT_ID ou EBAY_CLIENT_SECRET manquant"
    );
  }

  const basic = Buffer
    .from(`${id}:${secret}`)
    .toString("base64");

  const body = new URLSearchParams({
    grant_type: "client_credentials",
    scope: "https://api.ebay.com/oauth/api_scope"
  });

  const r = await fetch(
    API + "/identity/v1/oauth2/token",
    {
      method: "POST",

      headers: {
        Authorization: "Basic " + basic,
        "Content-Type":
          "application/x-www-form-urlencoded"
      },

      body
    }
  );

  const text = await r.text();

  if (!r.ok) {
    throw new Error(
      `OAuth eBay ${r.status} : ${text.slice(0,300)}`
    );
  }

  const data = JSON.parse(text);

  tokenCache = {
    value: data.access_token,
    expires:
      Date.now() +
      Number(data.expires_in || 7200) * 1000
  };

  return tokenCache.value;
}


async function ebaySearch(options, stats) {
  const filters = [
    "priceCurrency:EUR"
  ];

  if (options.condition) {
    filters.unshift(
      `conditionIds:{${options.condition}}`
    );
  }

  if (options.maximum) {
    filters.push(
      `price:[..${Number(options.maximum)}]`
    );
  }

  if (options.france) {
    filters.push("itemLocationCountry:FR");
  }

  const params = new URLSearchParams({
    filter: filters.join(","),
    limit: String(options.limit || 20)
  });

  if (options.query) {
    params.set("q", options.query);
  }

  if (options.category) {
    params.set(
      "category_ids",
      options.category
    );
  }

  if (options.epid) {
    params.set("epid", options.epid);
  }

  const token = await accessToken();

  stats.requests++;

  const r = await fetch(
    API +
      "/buy/browse/v1/item_summary/search?" +
      params,
    {
      headers: {
        Authorization: "Bearer " + token,
        "X-EBAY-C-MARKETPLACE-ID":
          process.env.EBAY_MARKETPLACE ||
          "EBAY_FR",
        "Accept-Language": "fr-FR"
      }
    }
  );

  const text = await r.text();

  if (!r.ok) {
    throw new Error(
      `eBay Browse ${r.status} : ${text.slice(0,300)}`
    );
  }

  const data = JSON.parse(text);

  return data.itemSummaries || [];
}


async function getSettings(pool) {
  const r = await pool.query(`
    SELECT key,value
    FROM settings
    WHERE key IN ('d_rep','d_fr','d_fx')
  `);

  const v = Object.fromEntries(
    r.rows.map(x => [x.key, x.value])
  );

  return {
    repairPct: num(v.d_rep) ?? 20,
    feePct: num(v.d_fr) ?? 13,
    feeFixed: num(v.d_fx) ?? 0.35
  };
}


async function resaleEstimate(
  pool,
  item,
  stats
) {
  const key = item.epid
    ? "epid:" + item.epid
    : "q:" + cleanTitle(item.title);

  const cached = await pool.query(`
    SELECT *
    FROM ebay_resale_cache
    WHERE search_key=$1
      AND updated_at >
          now() - interval '24 hours'
  `, [key]);

  if (cached.rowCount) {
    const r = cached.rows[0];

    return {
      value: num(r.resale_estimate),
      min: num(r.resale_min),
      max: num(r.resale_max),
      count: Number(r.sample_count),
      used: r.is_used
    };
  }

  const search = item.epid
    ? { epid: item.epid }
    : { query: cleanTitle(item.title) };

  const usable = list =>
    list
      .filter(x => !ACCESS.test(x.title || ""))
      .filter(
        x => !new RegExp(HS_WORDS.source, "iu").test(x.title || "")
      )
      .map(priceOf)
      .filter(v => v > 0);

  let list = await ebaySearch({
    ...search,
    condition: "3000",
    limit: 30
  }, stats);

  let estimate = robust(usable(list));
  let used = true;

  if (estimate.count < 3) {
    list = await ebaySearch({
      ...search,
      condition: OK,
      limit: 25
    }, stats);

    estimate = robust(usable(list));
    used = false;
  }

  if (
    estimate.value == null ||
    estimate.count < 3
  ) {
    return null;
  }

  await pool.query(`
    INSERT INTO ebay_resale_cache(
      search_key,
      resale_estimate,
      resale_min,
      resale_max,
      sample_count,
      is_used,
      updated_at
    )
    VALUES($1,$2,$3,$4,$5,$6,now())

    ON CONFLICT(search_key)
    DO UPDATE SET
      resale_estimate=EXCLUDED.resale_estimate,
      resale_min=EXCLUDED.resale_min,
      resale_max=EXCLUDED.resale_max,
      sample_count=EXCLUDED.sample_count,
      is_used=EXCLUDED.is_used,
      updated_at=now()
  `, [
    key,
    estimate.value,
    estimate.min,
    estimate.max,
    estimate.count,
    used
  ]);

  return {
    ...estimate,
    used
  };
}


async function scanAlert(
  pool,
  alert,
  settings
) {
  const stats = {
    requests: 0
  };

  const run = await pool.query(`
    INSERT INTO search_runs(
      query,
      category,
      status
    )
    VALUES($1,$2,'running')
    RETURNING id
  `, [
    alert.query || "",
    alert.category || ""
  ]);

  const runId = run.rows[0].id;

  try {
    const items = await ebaySearch({
      query: alert.query || "",
      category: alert.category || "",
      condition: HS,
      maximum:
        alert.maximum_purchase,
      france:
        alert.france_only,
      limit: 12
    }, stats);

    let saved = 0;
    const seenIds = [];

    for (const item of items) {
      const purchase =
        priceOf(item) +
        shippingOf(item);

      if (
        alert.maximum_purchase != null &&
        purchase >
          Number(alert.maximum_purchase)
      ) {
        continue;
      }

      const resale =
        await resaleEstimate(
          pool,
          item,
          stats
        );

      if (!resale) continue;

      const repair =
        resale.value *
        settings.repairPct /
        100;

      const fees =
        resale.value *
        settings.feePct /
        100 +
        settings.feeFixed;

      const margin =
        resale.value -
        purchase -
        repair -
        fees;

      const maximumPurchase =
        resale.value -
        repair -
        fees -
        Number(
          alert.minimum_margin || 40
        );

      if (
        margin <
        Number(
          alert.minimum_margin || 40
        )
      ) {
        continue;
      }

      await pool.query(`
        INSERT INTO listing_candidates(
          alert_id,
          search_run_id,
          ebay_item_id,
          title,
          url,
          image_url,
          seller,
          purchase_price,
          shipping_price,
          resale_estimate,
          repair_estimate,
          ebay_fee_estimate,
          net_margin,
          margin_percent,
          maximum_purchase,
          buying_type,
          french_listing,
          raw_data,
          detected_at,
          last_seen_at
        )
        VALUES(
          $1,$2,$3,$4,$5,$6,$7,
          $8,$9,$10,$11,$12,$13,$14,
          $15,$16,$17,$18::jsonb,
          now(),now()
        )

        ON CONFLICT(alert_id, ebay_item_id)
        WHERE alert_id IS NOT NULL
        DO UPDATE SET
          search_run_id=EXCLUDED.search_run_id,
          title=EXCLUDED.title,
          url=EXCLUDED.url,
          image_url=EXCLUDED.image_url,
          seller=EXCLUDED.seller,
          purchase_price=EXCLUDED.purchase_price,
          shipping_price=EXCLUDED.shipping_price,
          resale_estimate=EXCLUDED.resale_estimate,
          repair_estimate=EXCLUDED.repair_estimate,
          ebay_fee_estimate=EXCLUDED.ebay_fee_estimate,
          net_margin=EXCLUDED.net_margin,
          margin_percent=EXCLUDED.margin_percent,
          maximum_purchase=EXCLUDED.maximum_purchase,
          buying_type=EXCLUDED.buying_type,
          french_listing=EXCLUDED.french_listing,
          raw_data=EXCLUDED.raw_data,
          last_seen_at=now(),
          missing_runs=0,
          state=CASE
            WHEN listing_candidates.state='expired'
              THEN 'new'
            ELSE listing_candidates.state
          END
      `, [
        alert.id,
        runId,
        String(item.itemId),
        String(item.title || ""),
        item.itemWebUrl || null,
        item.image?.imageUrl || null,
        item.seller?.username || null,
        priceOf(item),
        shippingOf(item),
        resale.value,
        repair,
        fees,
        margin,
        purchase > 0
          ? margin / purchase * 100
          : null,
        maximumPurchase,
        (item.buyingOptions || [])
          .join(","),
        item.itemLocation?.country === "FR",
        JSON.stringify(item)
      ]);

      seenIds.push(String(item.itemId));
      saved++;
    }

    /*
     * Une annonce qui n'est plus rentable ou plus retournée
     * pendant 3 scans successifs est considérée comme expirée.
     */
    await pool.query(`
      UPDATE listing_candidates
      SET
        missing_runs=missing_runs+1,
        state=CASE
          WHEN missing_runs+1 >= 3 THEN 'expired'
          ELSE state
        END
      WHERE alert_id=$1
        AND state IN ('new','seen')
        AND NOT (ebay_item_id = ANY($2::text[]))
    `, [
      alert.id,
      seenIds
    ]);

    await pool.query(`
      UPDATE search_runs
      SET
        finished_at=now(),
        result_count=$2,
        ebay_requests=$3,
        status='completed'
      WHERE id=$1
    `, [
      runId,
      saved,
      stats.requests
    ]);

    return {
      alertId: alert.id,
      query:
        alert.query ||
        alert.category_name ||
        alert.category,
      found: items.length,
      saved,
      requests: stats.requests
    };

  } catch (error) {
    await pool.query(`
      UPDATE search_runs
      SET
        finished_at=now(),
        ebay_requests=$2,
        status='failed',
        error_message=$3
      WHERE id=$1
    `, [
      runId,
      stats.requests,
      String(error.message || error)
    ]);

    throw error;
  }
}


async function runAll(pool) {
  const alerts = await pool.query(`
    SELECT *
    FROM alerts
    WHERE enabled=true
    ORDER BY created_at
  `);

  const settings =
    await getSettings(pool);

  const results = [];

  for (const alert of alerts.rows) {
    try {
      results.push(
        await scanAlert(
          pool,
          alert,
          settings
        )
      );
    } catch (error) {
      results.push({
        alertId: alert.id,
        query:
          alert.query ||
          alert.category_name ||
          alert.category,
        error:
          String(
            error.message || error
          )
      });
    }
  }

  /*
   * Nettoyage uniquement des affaires ignorées/expirées
   * depuis plus de 30 jours.
   */
  await pool.query(`
    DELETE FROM listing_candidates
    WHERE state IN ('ignored','expired')
      AND COALESCE(actioned_at,last_seen_at,detected_at)
          < now() - interval '30 days'
  `);

  return {
    alerts: alerts.rowCount,
    settings,
    results
  };
}


export default async function registerEbayWatcher(
  app,
  pool
) {

  app.get("/api/watch/status", async () => {
    const a = await pool.query(`
      SELECT count(*)::int AS n
      FROM alerts
      WHERE enabled=true
    `);

    const r = await pool.query(`
      SELECT
        id,
        query,
        started_at,
        finished_at,
        result_count,
        ebay_requests,
        status,
        error_message
      FROM search_runs
      ORDER BY started_at DESC
      LIMIT 5
    `);

    return {
      configured:
        Boolean(
          process.env.EBAY_CLIENT_ID &&
          process.env.EBAY_CLIENT_SECRET
        ),

      running,

      enabledAlerts:
        a.rows[0].n,

      recentRuns: r.rows
    };
  });


  app.post("/api/watch/run", async (req, reply) => {
    if (running) {
      return reply.code(409).send({
        error:
          "Une recherche eBay est déjà en cours"
      });
    }

    running = true;

    try {
      return await runAll(pool);

    } finally {
      running = false;
    }
  });


  app.get(
    "/api/watch/candidates",
    async req => {

      const limit = Math.min(
        200,
        Math.max(
          1,
          Number(req.query?.limit || 50)
        )
      );

      const allowed = new Set([
        "new",
        "seen",
        "ignored",
        "tracked",
        "bought",
        "expired"
      ]);

      let states = String(
        req.query?.status || "new,seen"
      )
      .split(",")
      .map(x => x.trim())
      .filter(x => allowed.has(x));

      if (!states.length)
        states = ["new","seen"];

      const r = await pool.query(`
        SELECT
          lc.id,
          lc.alert_id,
          lc.ebay_item_id,
          lc.title,
          lc.url,
          lc.image_url,
          lc.seller,
          lc.purchase_price,
          lc.shipping_price,
          lc.resale_estimate,
          lc.repair_estimate,
          lc.ebay_fee_estimate,
          lc.net_margin,
          lc.margin_percent,
          lc.maximum_purchase,
          lc.buying_type,
          lc.french_listing,
          lc.detected_at,
          lc.last_seen_at,
          lc.state,
          lc.viewed_at,
          lc.actioned_at,
          lc.missing_runs,
          a.query AS alert_query,
          a.category_name AS alert_category

        FROM listing_candidates lc

        LEFT JOIN alerts a
          ON a.id=lc.alert_id

        WHERE lc.state = ANY($2::text[])

        ORDER BY
          CASE lc.state
            WHEN 'new' THEN 0
            ELSE 1
          END,
          lc.net_margin DESC NULLS LAST,
          lc.detected_at DESC

        LIMIT $1
      `, [
        limit,
        states
      ]);

      return r.rows;
    }
  );


  /*
   * Marque les affaires affichées dans l'application comme vues.
   */
  app.post(
    "/api/watch/candidates/seen",
    async req => {

      const ids = Array.isArray(req.body?.ids)
        ? req.body.ids
            .map(String)
            .slice(0,100)
        : [];

      if (!ids.length)
        return {updated:0};

      const r = await pool.query(`
        UPDATE listing_candidates
        SET
          state='seen',
          viewed_at=COALESCE(viewed_at,now())
        WHERE id = ANY($1::uuid[])
          AND state='new'
      `, [ids]);

      return {
        updated:r.rowCount
      };
    }
  );


  /*
   * Actions de la boîte de réception :
   * suivre / acheté / ignorer.
   */
  app.post(
    "/api/watch/candidates/:id/action",
    async (req, reply) => {

      const action = String(
        req.body?.action || ""
      );

      const states = {
        seen: "seen",
        ignored: "ignored",
        tracked: "tracked",
        bought: "bought"
      };

      if (!states[action]) {
        return reply.code(400).send({
          error:"Action invalide"
        });
      }

      const r = await pool.query(`
        UPDATE listing_candidates
        SET
          state=$2,
          viewed_at=COALESCE(viewed_at,now()),
          actioned_at=CASE
            WHEN $2 IN ('ignored','tracked','bought')
              THEN now()
            ELSE actioned_at
          END
        WHERE id=$1::uuid
        RETURNING *
      `, [
        req.params.id,
        states[action]
      ]);

      if (!r.rowCount) {
        return reply.code(404).send({
          error:"Affaire introuvable"
        });
      }

      return r.rows[0];
    }
  );

}