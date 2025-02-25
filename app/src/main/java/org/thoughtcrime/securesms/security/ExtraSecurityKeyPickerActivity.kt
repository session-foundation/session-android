package org.thoughtcrime.securesms.security

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity

class ExtraSecurityKeyPickerActivity : AppCompatActivity() {

    private lateinit var chatId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Interfaz sencilla de lista (podemos usar una ListView simple para seleccionar)
        val listView = ListView(this)
        setContentView(listView)

        // Obtener el ID del chat pasado por Intent
        chatId = intent.getStringExtra("chatId") ?: ""

        // Obtener todas las claves disponibles desde el gestor de seguridad
        val keys = ExtraSecurityManager.getAllKeys()
        // Construir la lista de nombres a mostrar (incluyendo opción de desactivar)
        val displayList = ArrayList<String>()
        displayList.add("Disable Extra Security")  // Opción para desactivar
        for (key in keys) {
            // Mostrar alias de la clave y el algoritmo entre paréntesis
            displayList.add("${key.alias} (${key.algorithm})")
        }

        // Usar un adaptador simple para mostrar la lista de claves
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayList)
        listView.adapter = adapter

        // Manejar la selección de un elemento de la lista
        listView.setOnItemClickListener { parent, view, position, id ->
            if (position == 0) {
                // El usuario seleccionó "Disable Extra Security"
                setResult(Activity.RESULT_OK, intent.putExtra("selectedKeyAlias", "NONE"))
            } else {
                // Seleccionó una de las claves disponibles
                val selectedKey = keys[position - 1]  // (restar 1 porque posición 0 es "Disable")
                setResult(Activity.RESULT_OK, intent.putExtra("selectedKeyAlias", selectedKey.alias))
            }
            finish()
        }
    }
}
