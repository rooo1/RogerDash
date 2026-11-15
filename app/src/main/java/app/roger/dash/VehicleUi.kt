package app.roger.dash

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.widget.Toast
import java.util.Locale

/** Choosing, adding and editing vehicles (shared by the Ride, Fuel and More tabs). */
object VehicleUi {

    /** "V-Strom 250 SX ▾" pill: tap to switch vehicle, add one, or edit this one. Null unless several vehicles are in use. */
    fun switcher(a: Activity, ui: Ui, onChanged: () -> Unit): View? {
        if (!DashHub.showVehicles) return null
        val v = DashHub.vehicle
        return ui.text("${v.name}  ▾", 14f, ui.ink, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
            background = GradientDrawable().apply { cornerRadius = ui.dpf(18f); setColor(ui.card) }
            layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.dp(12) }
            setOnClickListener { pick(a, ui, onChanged) }
        }
    }

    fun pick(a: Activity, ui: Ui, onChanged: () -> Unit) {
        val list = DashHub.vehicles
        val cur = DashHub.vehicle
        val items = list.map { (if (it.id == cur.id) "✓  " else "     ") + it.name } +
            listOf("＋  Add a scooter or bike", "✎  Edit ${cur.name}")
        AlertDialog.Builder(a).setTitle("Vehicle").setItems(items.toTypedArray()) { _, i ->
            when {
                i < list.size -> {
                    if (DashHub.ride != null && list[i].id != DashHub.ride?.vehicle) {
                        Toast.makeText(a, "Finish the ride being recorded first", Toast.LENGTH_LONG).show()
                    } else { DashHub.vehicle = list[i]; onChanged() }
                }
                i == list.size -> edit(a, ui, null, onChanged)
                else -> edit(a, ui, cur, onChanged)
            }
        }.show()
    }

    /** Add ([v] null: a GPS-only scooter or bike) or edit a vehicle: name, tank size, expected mileage. */
    fun edit(a: Activity, ui: Ui, v: Vehicle?, onChanged: () -> Unit) {
        fun num(hint: String, value: Double?) = ui.field(value?.let { "%.1f".format(Locale.US, it).removeSuffix(".0") } ?: "", hint).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val name = ui.field(v?.name ?: "", "Name, e.g. Activa")
        val tank = num("Tank size (litres)", v?.tankL)
        val kmpl = num("Expected mileage (km/l)", v?.typicalKmpl)
        fun caption(s: String) = ui.text(s, 12f, ui.soft).apply { setPadding(0, ui.dp(12), 0, 0) }
        val form = ui.column(
            caption("Name"), name, caption("Tank size, litres"), tank,
            caption("Mileage you expect, km/l (used until your full-tank fill-ups measure it)"), kmpl,
        ).apply { setPadding(ui.dp(22), ui.dp(4), ui.dp(22), 0) }
        val b = AlertDialog.Builder(a)
            .setTitle(if (v == null) "Add a scooter or bike" else "Edit ${v.name}")
            .setMessage(if (v == null || !v.dash) "No Bluetooth needed: rides are recorded with the phone's GPS, and range comes from the km you've ridden since your last full tank."
                else "Your ${DashHub.model.label}: its dash sends the fuel bars and odometer over Bluetooth.")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim().ifBlank { v?.name ?: "Scooter" }
                val t = tank.text.toString().toDoubleOrNull()?.takeIf { it in 1.0..40.0 } ?: v?.tankL ?: 5.0
                val k = kmpl.text.toString().toDoubleOrNull()?.takeIf { Range.plausible(it) } ?: v?.typicalKmpl ?: 45.0
                val saved = v?.copy(name = n, tankL = t, typicalKmpl = k)
                    ?: Vehicle(DashHub.newVehicleId(), n, dash = false, tankL = t, typicalKmpl = k)
                DashHub.saveVehicle(saved)
                if (v == null && DashHub.ride == null) DashHub.vehicle = saved // switch to the new one
                onChanged()
            }
            .setNegativeButton("Cancel", null)
        if (v != null && !v.dash && v.id != Vehicle.MAIN) b.setNeutralButton("Remove") { _, _ ->
            AlertDialog.Builder(a).setMessage("Remove ${v.name}? Its rides and fill-ups are kept.")
                .setPositiveButton("Remove") { _, _ -> DashHub.deleteVehicle(v); onChanged() }
                .setNegativeButton("Cancel", null).show()
        }
        b.show()
    }
}
