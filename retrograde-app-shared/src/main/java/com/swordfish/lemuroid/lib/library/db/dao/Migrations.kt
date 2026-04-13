package com.swordfish.lemuroid.lib.library.db.dao

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.swordfish.lemuroid.lib.library.ArcadeSubSystemRoms

object Migrations {
    val VERSION_17_18: Migration =
        object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                reclassify(database, "toaplan", ArcadeSubSystemRoms.TOAPLAN_ROMS)
                reclassify(database, "taito",   ArcadeSubSystemRoms.TAITO_ROMS)
                reclassify(database, "psikyo",  ArcadeSubSystemRoms.PSIKYO_ROMS)
                reclassify(database, "pgm",     ArcadeSubSystemRoms.PGM_ROMS)
                reclassify(database, "kaneko",  ArcadeSubSystemRoms.KANEKO_ROMS)
                reclassify(database, "cave",    ArcadeSubSystemRoms.CAVE_ROMS)
                reclassify(database, "technos", ArcadeSubSystemRoms.TECHNOS_ROMS)
                reclassify(database, "seta",    ArcadeSubSystemRoms.SETA_ROMS)
            }

            private fun reclassify(
                database: SupportSQLiteDatabase,
                systemId: String,
                roms: Set<String>,
            ) {
                val placeholders = roms.joinToString(",") { "?" }
                database.execSQL(
                    "UPDATE games SET systemId='$systemId' WHERE systemId='mame2003plus' AND fileName IN ($placeholders)",
                    roms.toTypedArray(),
                )
                database.execSQL(
                    "UPDATE games SET systemId='$systemId' WHERE systemId='fbneo' AND fileName IN ($placeholders)",
                    roms.toTypedArray(),
                )
            }
        }

    val VERSION_16_17: Migration =
        object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                reclassify(database, "galaxian", ArcadeSubSystemRoms.GALAXIAN_ROMS)
            }

            private fun reclassify(
                database: SupportSQLiteDatabase,
                systemId: String,
                roms: Set<String>,
            ) {
                val placeholders = roms.joinToString(",") { "?" }
                database.execSQL(
                    "UPDATE games SET systemId='$systemId' WHERE systemId='mame2003plus' AND fileName IN ($placeholders)",
                    roms.toTypedArray(),
                )
                database.execSQL(
                    "UPDATE games SET systemId='$systemId' WHERE systemId='fbneo' AND fileName IN ($placeholders)",
                    roms.toTypedArray(),
                )
            }
        }

    val VERSION_15_16: Migration =
        object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                reclassify(database, "dataeast", ArcadeSubSystemRoms.DATAEAST_ROMS)
            }

            private fun reclassify(
                database: SupportSQLiteDatabase,
                systemId: String,
                roms: Set<String>,
            ) {
                val placeholders = roms.joinToString(",") { "?" }
                database.execSQL(
                    "UPDATE games SET systemId='$systemId' WHERE systemId='mame2003plus' AND fileName IN ($placeholders)",
                    roms.toTypedArray(),
                )
                database.execSQL(
                    "UPDATE games SET systemId='$systemId' WHERE systemId='fbneo' AND fileName IN ($placeholders)",
                    roms.toTypedArray(),
                )
            }
        }

    val VERSION_14_15: Migration =
        object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                reclassify(database, "cps2", ArcadeSubSystemRoms.CPS2_ROMS)
                reclassify(database, "cps3", ArcadeSubSystemRoms.CPS3_ROMS)
            }

            private fun reclassify(
                database: SupportSQLiteDatabase,
                systemId: String,
                roms: Set<String>,
            ) {
                val placeholders = roms.joinToString(",") { "?" }
                database.execSQL(
                    "UPDATE games SET systemId='$systemId' WHERE systemId='mame2003plus' AND fileName IN ($placeholders)",
                    roms.toTypedArray(),
                )
                database.execSQL(
                    "UPDATE games SET systemId='$systemId' WHERE systemId='fbneo' AND fileName IN ($placeholders)",
                    roms.toTypedArray(),
                )
            }
        }

    val VERSION_13_14: Migration =
        object : Migration(13, 14) {
            // Reclassify CPS-1 games that were wrongly indexed as 'mame2003plus' or 'fbneo'.
            private val CPS1_ROMS = listOf(
                "3wonders.zip","1941.zip","1941j.zip","area88.zip","unsquad.zip","blockbl.zip",
                "captcomm.zip","captcommj.zip","captcommu.zip",
                "cawing.zip","cawingj.zip","cawingu.zip",
                "dino.zip","dinoj.zip","dinoa.zip",
                "dynwar.zip","dynwarj.zip","f1dream.zip",
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
                "tigeroad.zip","varth.zip","varthr1.zip",
                "willow.zip","willowj.zip",
            )

            override fun migrate(database: SupportSQLiteDatabase) {
                val placeholders = CPS1_ROMS.joinToString(",") { "?" }
                database.execSQL(
                    "UPDATE games SET systemId='cps1' WHERE systemId='mame2003plus' AND fileName IN ($placeholders)",
                    CPS1_ROMS.toTypedArray(),
                )
                database.execSQL(
                    "UPDATE games SET systemId='cps1' WHERE systemId='fbneo' AND fileName IN ($placeholders)",
                    CPS1_ROMS.toTypedArray(),
                )
            }
        }

    val VERSION_12_13: Migration =
        object : Migration(12, 13) {
            // Reclassify Neo Geo games that were wrongly indexed as 'mame2003plus'
            // (they reside in the arcade/ folder which biased MAME before the neogeo fix).
            private val NEOGEO_ROMS = listOf(
                "2020bb.zip","3countb.zip","sonicwi2.zip","sonicwi3.zip","alpham2.zip",
                "androdun.zip","aof.zip","aof2.zip","aof3.zip","b2b.zip","bangbead.zip",
                "bstars2.zip","bstars.zip","blazstar.zip","bjourney.zip","breakrev.zip",
                "burningf.zip","crsword.zip","crswd2bl.zip","doubledr.zip","dragonsh.zip",
                "eightman.zip","kabukikl.zip","fatfury1.zip","fatfury3.zip","fatfursp.zip",
                "ganryu.zip","garou.zip","gpilots.zip","goalx3.zip","jockeygp.zip",
                "karnovr.zip","kotm.zip","kotm2.zip","lasthope.zip","lresort.zip",
                "lbowling.zip","magdrop2.zip","magdrop3.zip","maglord.zip","matrim.zip",
                "mslug.zip","mslug3.zip","mslug4.zip","mslug5.zip","mslugx.zip",
                "mutnat.zip","nam1975.zip","neobombe.zip","neodrift.zip","neomrdo.zip",
                "turfmast.zip","nitd.zip","ncombat.zip","ncommand.zip","overtop.zip",
                "panicbom.zip","preisle2.zip","pulstar.zip","pbobblen.zip","pbobbl2n.zip",
                "rotd.zip","rbff1.zip","rbff2.zip","rbffspec.zip","roboarmy.zip","svc.zip",
                "samsho.zip","samsho2.zip","samsho3.zip","samsho4.zip","sengoku.zip",
                "sengoku2.zip","sengoku3.zip","shocktro.zip","shocktr2.zip","socbrawl.zip",
                "spinmast.zip","strhoop.zip","irrmaze.zip","kof94.zip","kof95.zip",
                "kof96.zip","kof97.zip","kof98.zip","kof99.zip","kof2000.zip","kof2001.zip",
                "kof2002.zip","kof2003.zip","lastblad.zip","lastbld2.zip","superspy.zip",
                "ssideki4.zip","trally.zip","tophuntr.zip","twinspri.zip","viewpoin.zip",
                "wakuwak7.zip","wjammers.zip","zedblade.zip","zintrckb.zip","zupapa.zip",
            )

            override fun migrate(database: SupportSQLiteDatabase) {
                val placeholders = NEOGEO_ROMS.joinToString(",") { "?" }
                database.execSQL(
                    "UPDATE games SET systemId='neogeo' WHERE systemId='mame2003plus' AND fileName IN ($placeholders)",
                    NEOGEO_ROMS.toTypedArray(),
                )
                // Also catch any that were in the fbneo bucket (fbneo/ folder users)
                database.execSQL(
                    "UPDATE games SET systemId='neogeo' WHERE systemId='fbneo' AND fileName IN ($placeholders)",
                    NEOGEO_ROMS.toTypedArray(),
                )
            }
        }

    val VERSION_11_12: Migration =
        object : Migration(11, 12) {
            // Reclassify Neo Geo games from the generic 'fbneo' system to the
            // dedicated 'neogeo' system so they appear under the SNK Neo Geo entry.
            private val NEOGEO_ROMS = listOf(
                "2020bb.zip","3countb.zip","sonicwi2.zip","sonicwi3.zip","alpham2.zip",
                "androdun.zip","aof.zip","aof2.zip","aof3.zip","b2b.zip","bangbead.zip",
                "bstars2.zip","bstars.zip","blazstar.zip","bjourney.zip","breakrev.zip",
                "burningf.zip","crsword.zip","crswd2bl.zip","doubledr.zip","dragonsh.zip",
                "eightman.zip","kabukikl.zip","fatfury1.zip","fatfury3.zip","fatfursp.zip",
                "ganryu.zip","garou.zip","gpilots.zip","goalx3.zip","jockeygp.zip",
                "karnovr.zip","kotm.zip","kotm2.zip","lasthope.zip","lresort.zip",
                "lbowling.zip","magdrop2.zip","magdrop3.zip","maglord.zip","matrim.zip",
                "mslug.zip","mslug3.zip","mslug4.zip","mslug5.zip","mslugx.zip",
                "mutnat.zip","nam1975.zip","neobombe.zip","neodrift.zip","neomrdo.zip",
                "turfmast.zip","nitd.zip","ncombat.zip","ncommand.zip","overtop.zip",
                "panicbom.zip","preisle2.zip","pulstar.zip","pbobblen.zip","pbobbl2n.zip",
                "rotd.zip","rbff1.zip","rbff2.zip","rbffspec.zip","roboarmy.zip","svc.zip",
                "samsho.zip","samsho2.zip","samsho3.zip","samsho4.zip","sengoku.zip",
                "sengoku2.zip","sengoku3.zip","shocktro.zip","shocktr2.zip","socbrawl.zip",
                "spinmast.zip","strhoop.zip","irrmaze.zip","kof94.zip","kof95.zip",
                "kof96.zip","kof97.zip","kof98.zip","kof99.zip","kof2000.zip","kof2001.zip",
                "kof2002.zip","kof2003.zip","lastblad.zip","lastbld2.zip","superspy.zip",
                "ssideki4.zip","trally.zip","tophuntr.zip","twinspri.zip","viewpoin.zip",
                "wakuwak7.zip","wjammers.zip","zedblade.zip","zintrckb.zip","zupapa.zip",
            )

            override fun migrate(database: SupportSQLiteDatabase) {
                val placeholders = NEOGEO_ROMS.joinToString(",") { "?" }
                database.execSQL(
                    "UPDATE games SET systemId='neogeo' WHERE systemId='fbneo' AND fileName IN ($placeholders)",
                    NEOGEO_ROMS.toTypedArray(),
                )
            }
        }

    val VERSION_10_11: Migration =
        object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_games_fileName`
                    ON `games` (`fileName`)
                    """.trimIndent(),
                )
            }
        }

    val VERSION_9_10: Migration =
        object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `downloaded_roms`(
                        `fileName` TEXT NOT NULL,
                        `fileSize` INTEGER NOT NULL,
                        `downloadedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`fileName`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_downloaded_roms_fileName`
                    ON `downloaded_roms` (`fileName`)
                    """.trimIndent(),
                )
            }
        }

    val VERSION_8_9: Migration =
        object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `datafiles`(
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `gameId` INTEGER NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `fileUri` TEXT NOT NULL,
                        `lastIndexedAt` INTEGER NOT NULL,
                        `path` TEXT, FOREIGN KEY(`gameId`
                    ) REFERENCES `games`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
                    """.trimIndent(),
                )

                database.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_datafiles_id` ON `datafiles` (`id`)
                    """.trimIndent(),
                )

                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_datafiles_fileUri` ON `datafiles` (`fileUri`)
                    """.trimIndent(),
                )

                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_datafiles_gameId` ON `datafiles` (`gameId`)
                    """.trimIndent(),
                )

                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_datafiles_lastIndexedAt` ON `datafiles` (`lastIndexedAt`)
                    """.trimIndent(),
                )
            }
        }
}
