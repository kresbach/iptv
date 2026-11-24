package app.iptv.tv.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.iptv.tv.data.CategoryPrefs
import app.iptv.tv.data.Prefs
import app.iptv.tv.data.Profiles
import app.iptv.tv.data.Repository

/** Kinderprofil verlassen geht nur mit PIN (sofern eine festgelegt ist). */
private fun needsPin(target: Profiles.Profile) = Prefs.hasPin && Profiles.isKids && !target.kids

/** PIN-Abfrage vor dem Wechsel; ruft danach [onDone]. */
@Composable
private fun ProfileSwitcher(onDone: () -> Unit, content: @Composable (select: (Profiles.Profile) -> Unit) -> Unit) {
    var pending by remember { mutableStateOf<Profiles.Profile?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val target = pending
    if (target != null) {
        PinPad("PIN eingeben", message ?: "Zum Wechseln in „${target.name}“ wird die PIN gebraucht", onComplete = { pin ->
            if (CategoryPrefs.unlock(pin)) {
                pending = null; message = null
                Repository.switchProfile(target.id)
                onDone()
            } else message = "Falsche PIN – bitte erneut versuchen."
        })
        return
    }
    content { p ->
        if (needsPin(p)) pending = p
        else { Repository.switchProfile(p.id); onDone() }
    }
}

@Composable
private fun ProfileAvatar(p: Profiles.Profile, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (p.kids) AppColors.OrangeLight else AppColors.Orange),
        contentAlignment = Alignment.Center,
    ) {
        Text(p.name.take(1).uppercase(), fontSize = (size / 2.2).sp, fontWeight = FontWeight.Bold)
    }
}

/** Beim Start mit mehreren Profilen: „Wer schaut?“ */
@Composable
fun ProfilePicker(onChosen: () -> Unit) {
    val profiles by Profiles.list.collectAsState()
    val activeId by Profiles.activeId.collectAsState()
    val memory = remember { ScreenMemory() }
    ProfileSwitcher(onDone = onChosen) { select ->
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Wer schaut?", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                profiles.forEach { p ->
                    FocusSurface(
                        onClick = { select(p) },
                        modifier = Modifier.width(180.dp).rememberFocus(memory, "p-${p.id}", isDefault = p.id == activeId),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            ProfileAvatar(p, 96)
                            Spacer(Modifier.height(12.dp))
                            Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            if (p.kids) Text("Kinder", fontSize = 13.sp, color = AppColors.OrangeLight)
                        }
                    }
                }
            }
        }
    }
}

/** Profile verwalten: wechseln, hinzufügen, umbenennen, löschen. */
@Composable
fun ProfilesScreen(nav: Navigator, memory: ScreenMemory) {
    val context = LocalContext.current
    val profiles by Profiles.list.collectAsState()
    val activeId by Profiles.activeId.collectAsState()
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    remember { memory.restored = false; 0 }

    ProfileSwitcher(onDone = { nav.home() }) { select ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 64.dp, vertical = 32.dp).widthIn(max = 900.dp),
        ) {
            ScreenTitle(
                "Profile",
                "Jedes Profil hat eigene Favoriten, eigenen Verlauf und „Weiterschauen“. Das Kinderprofil zeigt nur " +
                    "Kinderinhalte (Kinder-Kategorien und Kindersender wie KiKA, Disney, Toggo; weitere unter „Kinderinhalte festlegen“). " +
                    "Zurück ins eigene Profil geht es nur mit der PIN. " +
                    "Der Abgleich mit dem iPhone betrifft nur das Profil „${profiles.first().name}“.",
            )
            profiles.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(AppColors.Surface).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProfileAvatar(p, 40)
                    if (renaming == p.id) {
                        TvTextField(renameText, { renameText = it }, "Neuer Name", Modifier.weight(1f), keyboardType = KeyboardType.Text)
                        SmallButton("Speichern", Modifier.rememberFocus(memory, "save-${p.id}")) { Profiles.rename(p.id, renameText); renaming = null }
                    } else {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(if (p.kids) "Kinderprofil" else null, if (p.id == activeId) "aktiv" else null).joinToString(" · ").ifEmpty { " " },
                                fontSize = 13.sp, color = AppColors.OrangeLight,
                            )
                        }
                        if (p.id != activeId) {
                            SmallButton("Wechseln", Modifier.rememberFocus(memory, "use-${p.id}", isDefault = true)) { select(p) }
                        }
                        SmallButton("Umbenennen", Modifier.rememberFocus(memory, "ren-${p.id}")) { renaming = p.id; renameText = p.name }
                        if (p.id != Profiles.MAIN) {
                            SmallButton(if (confirmDelete == p.id) "Wirklich löschen?" else "Löschen", Modifier.rememberFocus(memory, "del-${p.id}")) {
                                if (confirmDelete == p.id) {
                                    Profiles.remove(p.id, context.filesDir)
                                    confirmDelete = null
                                } else confirmDelete = p.id
                            }
                        }
                    }
                }
            }
            if (profiles.any { it.kids }) {
                Spacer(Modifier.height(12.dp))
                AppButton("Kinderinhalte festlegen", { nav.push(Screen.CategoryEdit(app.iptv.tv.model.ContentKind.LIVE)) },
                    primary = false, modifier = Modifier.rememberFocus(memory, "kidsContent"))
            }
            Spacer(Modifier.height(20.dp))
            SettingsHeader("Neues Profil")
            TvTextField(newName, { newName = it }, "Name (optional)", placeholder = "z. B. Lisa", keyboardType = KeyboardType.Text)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton("Profil hinzufügen", {
                    Profiles.add(newName, kids = false); newName = ""
                    Toast.makeText(context, "Profil angelegt", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.rememberFocus(memory, "add"))
                AppButton("Kinderprofil hinzufügen", {
                    Profiles.add(newName, kids = true); newName = ""
                    Toast.makeText(
                        context,
                        if (Prefs.hasPin) "Kinderprofil angelegt" else "Kinderprofil angelegt – bitte unter Einstellungen → Kindersicherung eine PIN festlegen",
                        Toast.LENGTH_LONG,
                    ).show()
                }, primary = false, modifier = Modifier.rememberFocus(memory, "addKids"))
            }
            if (Profiles.isKids && !Prefs.hasPin) {
                Spacer(Modifier.height(12.dp))
                Text("Hinweis: Ohne PIN kann das Kinderprofil jederzeit verlassen werden.", fontSize = 14.sp, color = AppColors.OrangeLight)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
