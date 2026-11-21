package com.paragrudani.replyly.policy

/**
 * Banking, UPI, wallet and broker apps that Replyly never reads (spec 4), on top of the user's own
 * blocklist. To add one: open the app, take a debug dump (hidden debug screen), copy its
 * `packageName` here. Exact package names only, no prefixes.
 */
object SensitiveApps {
    val PACKAGES: Set<String> =
        setOf(
            // State Bank of India
            "com.sbi.lotusintouch", // YONO SBI
            "com.sbi.SBIFreedomPlus", // YONO Lite SBI
            "com.sbi.upi", // BHIM SBI Pay
            // HDFC Bank
            "com.snapwork.hdfc", // HDFC Bank MobileBanking
            "com.enstage.wibmo.hdfc", // PayZapp
            // ICICI Bank
            "com.csam.icici.bank.imobile", // iMobile Pay
            // Axis Bank
            "com.axis.mobile",
            // Kotak Mahindra Bank
            "com.msf.kbank.mobile", // Kotak Bank / 811
            // Other banks
            "com.bankofbaroda.mconnect", // bob World
            "com.Version1", // PNB ONE
            "com.canarabank.mobility", // Canara ai1
            "com.idfcfirstbank.optimus", // IDFC FIRST Bank
            "com.fss.indus", // IndusInd Bank
            "com.infrasoft.uboi", // Union Bank Vyom
            // UPI and wallets
            "com.phonepe.app", // PhonePe
            "net.one97.paytm", // Paytm
            "com.google.android.apps.nbu.paisa.user", // Google Pay (India)
            "in.org.npci.upiapp", // BHIM
            "com.dreamplug.androidapp", // CRED
            "in.amazon.mShop.android.shopping", // Amazon (India), home of Amazon Pay
            "com.mobikwik_new", // MobiKwik
            "com.freecharge.android", // Freecharge
            "com.google.android.apps.walletnfcrel", // Google Wallet
            "com.samsung.android.spay", // Samsung Wallet
            // Investing
            "com.nextbillion.groww", // Groww
            "com.zerodha.kite3", // Zerodha Kite
            "com.paytmmoney", // Paytm Money
        )
}
