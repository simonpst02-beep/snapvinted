import { randomUUID } from "node:crypto";

const num = v => {
  if (v === "" || v === null || v === undefined) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
};

export default async function registerBackupRoutes(app, pool) {

  app.post("/api/import/backup", async (req, reply) => {
    if (String(req.query?.replace || "") !== "1") {
      return reply.code(400).send({
        error: "Ajoute ?replace=1 pour confirmer l'import complet"
      });
    }

    const o = req.body || {};

    if (!["spyebay", "margeo"].includes(o.app)) {
      return reply.code(400).send({
        error: "Sauvegarde SpyEbay invalide"
      });
    }

    const c = await pool.connect();

    try {
      await c.query("BEGIN");

      const snap = await c.query(`
        INSERT INTO mobile_snapshots(app_version, payload)
        VALUES($1,$2::jsonb)
        RETURNING id
      `, [
        Number(o.version || 1),
        JSON.stringify(o)
      ]);

      await c.query("DELETE FROM tracked_items");
      await c.query("DELETE FROM alerts");
      await c.query("DELETE FROM stock_parts");
      await c.query("DELETE FROM watched_sellers");
      await c.query("DELETE FROM recent_searches");
      await c.query("DELETE FROM profiles WHERE id <> 'moi'");

      await c.query(`
        INSERT INTO profiles(id,name)
        VALUES('moi','Moi')
        ON CONFLICT(id) DO UPDATE SET name='Moi'
      `);

      const profils = Array.isArray(o.profils) ? o.profils : [];

      for (const p of profils) {
        if (!p?.id) continue;

        await c.query(`
          INSERT INTO profiles(id,name)
          VALUES($1,$2)
          ON CONFLICT(id)
          DO UPDATE SET name=EXCLUDED.name
        `, [
          String(p.id),
          String(p.nom || p.id)
        ]);
      }

      for (const key of Object.keys(o)) {
        if (!key.startsWith("suivi_")) continue;

        const id = key.slice(6);

        if (!id || !Array.isArray(o[key])) continue;

        await c.query(`
          INSERT INTO profiles(id,name)
          VALUES($1,$1)
          ON CONFLICT(id) DO NOTHING
        `, [id]);
      }

      let tracked = 0;

      const imports = [
        ["moi", Array.isArray(o.suivi) ? o.suivi : []]
      ];

      for (const p of profils) {
        if (!p?.id || p.id === "moi") continue;

        imports.push([
          String(p.id),
          Array.isArray(o["suivi_" + p.id])
            ? o["suivi_" + p.id]
            : []
        ]);
      }

      for (const [profileId, items] of imports) {
        for (const x of items) {
          if (!x?.id || !x?.t) continue;

          const statut = [
            "repere",
            "achete",
            "repar",
            "vente",
            "vendu"
          ].includes(x.statut) ? x.statut : "repere";

          await c.query(`
            INSERT INTO tracked_items(
              id,
              profile_id,
              ebay_item_id,
              title,
              image_url,
              icon,
              source_url,
              purchase_estimated,
              resale_estimated,
              quantity,
              resale_min,
              resale_max,
              resale_is_used,
              margin_estimated,
              status,
              purchase_actual,
              parts_cost,
              sale_actual,
              parts_ordered,
              delivery_date,
              notes,
              status_dates,
              created_at
            )
            VALUES(
              $1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,
              $12,$13,$14,$15,$16,$17,$18,$19,$20,
              $21,$22::jsonb,
              COALESCE(to_timestamp($23 / 1000.0), now())
            )
            ON CONFLICT(id) DO UPDATE SET
              profile_id=EXCLUDED.profile_id,
              ebay_item_id=EXCLUDED.ebay_item_id,
              title=EXCLUDED.title,
              image_url=EXCLUDED.image_url,
              icon=EXCLUDED.icon,
              source_url=EXCLUDED.source_url,
              purchase_estimated=EXCLUDED.purchase_estimated,
              resale_estimated=EXCLUDED.resale_estimated,
              quantity=EXCLUDED.quantity,
              resale_min=EXCLUDED.resale_min,
              resale_max=EXCLUDED.resale_max,
              resale_is_used=EXCLUDED.resale_is_used,
              margin_estimated=EXCLUDED.margin_estimated,
              status=EXCLUDED.status,
              purchase_actual=EXCLUDED.purchase_actual,
              parts_cost=EXCLUDED.parts_cost,
              sale_actual=EXCLUDED.sale_actual,
              parts_ordered=EXCLUDED.parts_ordered,
              delivery_date=EXCLUDED.delivery_date,
              notes=EXCLUDED.notes,
              status_dates=EXCLUDED.status_dates
          `, [
            String(x.id),
            profileId,
            x.itemId || null,
            String(x.t),
            x.img || null,
            x.ico || null,
            x.url || null,
            num(x.achatPrev),
            num(x.reventePrev),
            Math.max(1, Number(x.qty || 1)),
            num(x.min),
            num(x.max),
            x.occ == null ? null : Boolean(x.occ),
            num(x.margePrev),
            statut,
            num(x.achat),
            num(x.pieces),
            num(x.vente),
            x.commande || null,
            x.livraison || null,
            String(x.notes || ""),
            JSON.stringify(x.dates || {}),
            num(x.date)
          ]);

          tracked++;
        }
      }

      let alerts = 0;

      for (const x of Array.isArray(o.alerts) ? o.alerts : []) {
        const id = String(x.id || randomUUID());

        await c.query(`
          INSERT INTO alerts(
            id,query,category,category_name,
            minimum_margin,maximum_purchase,
            france_only,enabled
          )
          VALUES($1,$2,$3,$4,$5,$6,$7,true)
        `, [
          id,
          x.q || "",
          x.cat || "",
          x.catName || "",
          num(x.mmin) ?? 40,
          num(x.pmax),
          Boolean(x.fr)
        ]);

        alerts++;
      }

      let stock = 0;

      for (const x of Array.isArray(o.stock) ? o.stock : []) {
        if (!x?.nom) continue;

        await c.query(`
          INSERT INTO stock_parts(name,quantity,unit_price)
          VALUES($1,$2,$3)
        `, [
          String(x.nom),
          Math.max(0, Number(x.qte || 0)),
          Math.max(0, num(x.prix) || 0)
        ]);

        stock++;
      }

      for (const s of Array.isArray(o.vendeurs) ? o.vendeurs : []) {
        await c.query(`
          INSERT INTO watched_sellers(username)
          VALUES($1)
          ON CONFLICT(username) DO NOTHING
        `, [String(s)]);
      }

      const recent = Array.isArray(o.recent) ? o.recent : [];

      for (let i = 0; i < recent.length; i++) {
        await c.query(`
          INSERT INTO recent_searches(query,searched_at)
          VALUES(
            $1,
            now() - ($2::integer * interval '1 second')
          )
        `, [
          String(recent[i]),
          i
        ]);
      }

      const settingKeys = [
        "d_rep","d_fr","d_fx","d_target",
        "learn","al_on","ai_photos",
        "u_auto","ebay_mk",
        "c_taux","c_vl",
        "th_theme","th_accent","th_size",
        "u_dl"
      ];

      for (const key of settingKeys) {
        if (o[key] == null) continue;

        await c.query(`
          INSERT INTO settings(key,value)
          VALUES($1,$2::jsonb)
          ON CONFLICT(key)
          DO UPDATE SET value=EXCLUDED.value
        `, [
          key,
          JSON.stringify(o[key])
        ]);
      }

      if (o.price_hist) {
        await c.query(`
          INSERT INTO settings(key,value)
          VALUES('price_hist',$1::jsonb)
          ON CONFLICT(key)
          DO UPDATE SET value=EXCLUDED.value
        `, [JSON.stringify(o.price_hist)]);
      }

      await c.query("COMMIT");

      return {
        ok: true,
        snapshot: snap.rows[0].id,
        tracked,
        alerts,
        stock,
        profiles: profils.length || 1,
        sellers: Array.isArray(o.vendeurs) ? o.vendeurs.length : 0,
        recent: recent.length
      };

    } catch (error) {
      await c.query("ROLLBACK");

      app.log.error(error);

      return reply.code(500).send({
        error: error.message
      });

    } finally {
      c.release();
    }
  });
}
