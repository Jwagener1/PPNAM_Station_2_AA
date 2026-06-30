package com.ppnam.station2aa.navigation

object NavRoutes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val JOB_LOOKUP = "mixing/job_lookup"
    const val INGREDIENT_SCAN = "mixing/ingredient_scan/{orderNo}"
    const val HOPPER_SCAN = "mixing/hopper_scan/{orderNo}"
    const val PREMIX_COMPLETE = "mixing/premix_complete/{orderNo}"
    const val MACHINE_SELECT = "rajoo/machine_select"
    const val PALLET_ALLOC = "rajoo/pallet_alloc/{machineCode}"
    const val RFID_RECOVERY = "rfid/recovery"
    const val DASHBOARD = "dashboard"

    fun ingredientScan(orderNo: String) = "mixing/ingredient_scan/$orderNo"
    fun hopperScan(orderNo: String) = "mixing/hopper_scan/$orderNo"
    fun premixComplete(orderNo: String) = "mixing/premix_complete/$orderNo"
    fun palletAlloc(machineCode: String) = "rajoo/pallet_alloc/$machineCode"
}
