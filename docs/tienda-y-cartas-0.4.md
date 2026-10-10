# Tienda y cartas · TowerBot 0.4.0

El regalo gratuito de 20 gemas se comprueba desde Inicio al arrancar el bot y después
de cada muerte. Se navega al Inicio en lugar de usar directamente REINTENTAR, se busca
el recuadro gratuito y se verifica por separado el botón disponible. Sólo entonces
se abre el video. Los cierres y RECLAMAR tienen prioridad sobre navegación y compras.
La ausencia del regalo, un contador o un botón diferente permiten continuar la partida.
Desactivar anuncios de gemas también desactiva esta visita aunque su opción siga marcada.

Se revisó en este teléfono un inventario de 18 cartas y 4 espacios. El mazo inicial
es Dinero, Monedas, Equilibrio enemigo y Salud. El lector recorre el inventario entre
partidas, reconoce tarjetas por sus separadores y títulos y cuenta sus estrellas.
Excluye recuadros con candado. La burbuja se oculta durante esta operación para no tapar
los títulos. Una lectura incompleta conserva el inventario anterior y espera.

Cada nivel mantiene su campeona y retadora. Las estrategias contienen ahora las compras
y el mazo. Cada mutación cambia compras o sustituye una carta por otra que el usuario
posee, manteniendo el otro componente. El bot elige la estrategia antes de BATALLA,
equipa el mazo y verifica sus marcas verdes en el inventario y el contador de espacios. Guarda esa misma
estrategia al comenzar la ronda; no vuelve a sortear otra.

El objetivo seleccionable de oleadas compara las oleadas finales por pares completos
de campeona/retadora. Monedas/min sigue registrado. Cambiar objetivo o espacios reinicia
los pares; conserva las compras campeonas y la generación. Antes también los reiniciaban
las cartas nuevas y las estrellas, y en una semana sólo se cerró un duelo: cada revisión
del inventario borraba los pares de todos los niveles. Un mazo con una carta que ya no
aparece se completa con las del inventario. Las rondas tomadas a mitad, mazos no verificados y cambios
de configuración durante una ronda no cuentan para aprender.

La calibración de Tienda y la geometría del inventario son específicas de pantalla e
idioma. Se incluyen en el perfil actualizado de 1080×2400. Los nuevos cierres de anuncios
se añaden en el editor habitual. Al desbloquear un espacio, el bot lee la capacidad nueva
del contador (dos lecturas iguales seguidas) al revisar el inventario. Cada mazo conserva
sus cartas y suma la que pusiste tú en el juego o, si no, la de más estrellas. No hace falta
recalibrar. Si aun así no logra comprobar el mazo tres veces seguidas, juega una hora con el
mazo puesto, sin contar esas partidas, y lo vuelve a intentar.
El recorte del contador debe incluir la palabra ACTIVO encima del número: un «0/4»
aislado puede hacer que el OCR lo interprete girado. Los nombres se comparan sin acentos,
espacios ni saltos de línea, y con una letra de diferencia por cada diez: la misma carta
sale en dos páginas seguidas y una vez se leyó «Probabilidad de críitico». Esa carta
fantasma hacía fallar todas las revisiones siguientes.
La fila de cartas activas se puede desplazar horizontalmente y el juego la mueve al
equipar cartas. Por eso el bot selecciona y verifica desde el inventario, donde las
cartas mantienen su columna. Cada cambio se comprueba con el contador y la marca verde.

## Validación

36 pruebas unitarias pasan y `assembleDebug` genera el APK. Las pruebas cubren visita
a Tienda entre rondas, premio disponible/agotado, regreso al juego, cambio de puntuación
a oleadas, mutaciones limitadas al inventario, lectura de tarjetas con borde cian,
estrellas, exclusión de candados, sustitución completa, bordes verdes que no son marcas
de selección y rechazo de mazos/capacidades inválidos.

En el teléfono se comprobó la solicitud automática del video de Tienda, se amplió la
calibración del cierre y se reclamó automáticamente el premio: 358 → 378 gemas. La
siguiente comprobación detectó que ya no estaba disponible y continuó a Cartas.

También se comprobó la lectura de las 18 cartas y estrellas, el equipamiento de las
cuatro cartas iniciales y sus marcas verdes. El contador confirmó 4/4; el bot inició
una ronda de Nivel 1 con la misma estrategia preparada (`pdf`) y abrió el bonus de
monedas. Los nuevos duelos se puntúan en oleadas, sin reutilizar las puntuaciones de
monedas/min anteriores. Todavía no hay pares completos con cartas para afirmar una
mejora de rendimiento.

El cierre circular de este proveedor se calibró sin reiniciar la ronda. El bot volvió
al juego y cerró automáticamente el aviso «Error al cargar anuncio» que apareció
después. La partida continúa en Nivel 1, con el bonus de monedas activo y las tres
opciones nuevas habilitadas. Se restauró el ajuste temporal de mantener despierto por
USB; la burbuja del bot mantiene la pantalla encendida mientras trabaja.
