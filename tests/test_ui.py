"""Tests automatiques de l'interface SpyEbay (mode démo), lancés par GitHub avant chaque compilation."""
import asyncio, pathlib, sys
from playwright.async_api import async_playwright

PAGE = (pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/index.html").as_uri()
echecs = []

def verifier(cond, msg):
    print(("✓ " if cond else "✗ ") + msg)
    if not cond:
        echecs.append(msg)

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        ctx = await b.new_context(viewport={"width": 390, "height": 844}, accept_downloads=True)
        pg = await ctx.new_page()
        erreurs = []
        pg.on("pageerror", lambda e: erreurs.append(str(e)))
        pg.on("console", lambda m: erreurs.append(m.text) if m.type == "error" else None)
        await pg.goto(PAGE)
        await pg.wait_for_timeout(500)

        # Guide de démarrage au premier lancement
        verifier(await pg.locator("#onb").is_visible(), "le guide s'affiche au premier lancement")
        await pg.click("#onbSkip")
        verifier(await pg.locator("#onb").is_hidden(), "le guide se ferme")

        # Recherche (démo)
        n = await pg.locator("#list .card").count()
        verifier(n >= 10, f"les annonces de démo s'affichent ({n})")
        await pg.fill("#q", "iphone")
        await pg.wait_for_timeout(200)
        verifier(await pg.locator("#list .card").count() == 1, "le filtre par mot-clé fonctionne")
        await pg.fill("#q", "")
        await pg.dispatch_event("#q", "input")

        # Détail, descriptif, enchère
        carte = pg.locator("#list .card").filter(has_text="Enchère").first
        await carte.click()
        await carte.get_by_text("Voir le descriptif").click()
        await pg.wait_for_timeout(200)
        verifier(await carte.locator(".desc").is_visible(), "le descriptif s'affiche")
        verifier("prix final estimé" in (await carte.inner_text()), "l'estimation d'enchère est affichée")

        # Calculs
        r = await pg.evaluate("[robuste([5,200,210,220,230,600,190]).v, parseLot('Lot de 3 manettes'), propre('iPhone 12 écran cassé HS')]")
        verifier(r[0] == 210, "les prix aberrants sont écartés")
        verifier(r[1] == 3, "les lots sont détectés")
        verifier(r[2] == "iphone 12 écran", "les titres sont nettoyés")

        # Suivi
        for i in range(2):
            await pg.locator("#list .star").nth(i).click()
        await pg.click("#navT")
        await pg.wait_for_timeout(200)
        verifier(await pg.locator("#tList .card").count() == 2, "le suivi contient les annonces étoilées")
        c = pg.locator("#tList .card").first
        await c.locator(".row").click()
        champ = c.locator("input").nth(2)
        await champ.fill("320")
        await champ.dispatch_event("change")
        await pg.wait_for_timeout(200)
        verifier("Vendu" in await pg.inner_text("#tList"), "une vente passe l'appareil en « Vendu »")
        verifier((await pg.inner_text("#tCompta")).strip() != "", "le récap comptable s'affiche")
        async with pg.expect_download() as dl:
            await pg.click("#tCsv")
        verifier((await dl.value).suggested_filename.endswith(".csv"), "l'export tableur fonctionne")

        # Profils
        await pg.click("#tProf button[data-p='+']")
        await pg.fill("#pNom", "Lucas")
        await pg.click("#pAdd")
        await pg.click("#pClose")
        await pg.wait_for_timeout(200)
        verifier(await pg.locator("#tList .card").count() == 0, "un nouveau profil a son propre suivi")
        await pg.click("#tProf button[data-p=tous]")
        await pg.wait_for_timeout(200)
        verifier(await pg.locator("#tList .card").count() == 2, "« Ensemble » regroupe tous les profils")

        # Historique des prix (démo)
        await pg.click("#navS")
        verifier(await pg.locator("#list .spark").count() > 0, "la tendance des prix s'affiche sur les cartes")

        # Partage d'un lien (démo : demande les clés)
        await pg.evaluate("window.__partage('Regarde https://www.ebay.fr/itm/123456789012')")
        await pg.wait_for_timeout(200)
        verifier("clés eBay" in await pg.inner_text("#msg"), "un lien partagé est reconnu")

        # Pièces AliExpress
        await pg.evaluate("view('vAI');findParts([{nom:'Écran',recherche:'ecran iphone 12',recherche_en:'iphone 12 screen',ali_min:20,ali_max:30}],null)")
        await pg.wait_for_timeout(300)
        verifier("aliexpress.com/w/wholesale-iphone-12-screen" in await pg.inner_html("#chat"), "le lien AliExpress des pièces est créé")

        # Paramètres et thèmes
        await pg.click("#navP")
        await pg.click("#thTheme button[data-v=light]")
        verifier(await pg.evaluate("document.documentElement.dataset.theme") == "light", "le thème clair s'applique")
        await pg.click("#thTheme button[data-v=dark]")
        await pg.click("#thAccent button[data-v=sunset]")
        verifier(await pg.evaluate("document.documentElement.dataset.accent") == "sunset", "la couleur d'accent change")

        # Assistant sans clé
        await pg.click("#navA")
        verifier("clé API Claude" in await pg.inner_text("#chat"), "l'assistant demande la clé")

        verifier(not erreurs, "aucune erreur JavaScript" + (f" : {erreurs}" if erreurs else ""))
        await b.close()

asyncio.run(main())
print(f"\n{'ÉCHEC' if echecs else 'OK'} : {len(echecs)} problème(s)")
sys.exit(1 if echecs else 0)
