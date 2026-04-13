import sqlite3

# Update libretro-db: tag 76 CPS1 ROMs as system=cps1
CPS1_ROMS = [
    "1941.zip","1941j.zip",
    "area88.zip","unsquad.zip",
    "blockbl.zip",
    "captcomm.zip","captcommj.zip","captcommu.zip",
    "cawing.zip","cawingj.zip","cawingu.zip",
    "dino.zip","dinoj.zip","dinoa.zip",
    "dynwar.zip","dynwarj.zip",
    "f1dream.zip",
    "ffight.zip","ffightu.zip","ffightua.zip","ffightub.zip","ffightj.zip","ffightj1.zip","ffightbl.zip",
    "forgottn.zip","lostwrld.zip",
    "ghouls.zip","ghoulsu.zip","daimakai.zip",
    "kod.zip","kodj.zip","kodu.zip",
    "knights.zip","knightsj.zip","knightsu.zip",
    "mercs.zip","mercsj.zip","mercsu.zip",
    "mbomberj.zip","mbombrd.zip","mbombrdj.zip",
    "nemo.zip","nemoj.zip",
    "punisher.zip","punisherj.zip","punisheru.zip",
    "qad.zip",
    "sf2.zip","sf2j.zip","sf2jl.zip","sf2ue.zip","sf2uf.zip","sf2ui.zip","sf2uk.zip",
    "sf2b.zip","sf2b2.zip","sf2accp2.zip",
    "sf2ce.zip","sf2ceua.zip","sf2ceub.zip","sf2ceuc.zip","sf2ceea.zip","sf2cejb.zip","sf2ceb.zip",
    "sf2hf.zip","sf2hfu.zip","sf2hfj.zip",
    "strider.zip","striderj.zip","striderua.zip",
    "3wonders.zip",
    "tigeroad.zip",
    "varth.zip","varthr1.zip",
    "willow.zip","willowj.zip",
]

db_path = r'E:\projects\lemuroid\Lemuroid\lemuroid-metadata-libretro-db\src\main\assets\libretro-db.sqlite'
conn = sqlite3.connect(db_path)
c = conn.cursor()

c.execute("PRAGMA user_version")
print("user_version:", c.fetchone())
c.execute("SELECT COUNT(*) FROM games WHERE system='neogeo'")
print("neogeo rows:", c.fetchone())
c.execute("SELECT COUNT(*) FROM games WHERE system='cps1'")
print("cps1 rows before:", c.fetchone())

placeholders = ",".join("?" for _ in CPS1_ROMS)
c.execute(f"UPDATE games SET system='cps1' WHERE system='fbneo' AND romName IN ({placeholders})", CPS1_ROMS)
print("Updated rows:", c.rowcount)

c.execute("SELECT COUNT(*) FROM games WHERE system='cps1'")
print("cps1 rows after:", c.fetchone())
conn.commit()
conn.close()
print("Done.")
