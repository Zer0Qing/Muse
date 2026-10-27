<p align="center">
  <img src="assets/readme/banner.png" width="100%" alt="Muse: no solo conversación, una IA que te conoce de verdad">
</p>

<p align="center">
  <a href="README.md">中文</a> · <a href="README_EN.md">English</a> · <a href="README_JA.md">日本語</a> · <a href="README_KO.md">한국어</a> · <b>Español</b> · <a href="README_PT.md">Português</a> · <a href="README_RU.md">Русский</a>
</p>

<p align="center">
  <a href="https://github.com/Zer0Qing/Muse/releases/latest"><img src="https://img.shields.io/github/v/release/Zer0Qing/Muse?style=flat-square&label=release" alt="Última versión"></a>
  <a href="https://github.com/Zer0Qing/Muse/releases"><img src="https://img.shields.io/github/downloads/Zer0Qing/Muse/total?style=flat-square" alt="Descargas"></a>
  <a href="https://github.com/Zer0Qing/Muse/stargazers"><img src="https://img.shields.io/github/stars/Zer0Qing/Muse?style=flat-square" alt="Estrellas"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPLv3-blue.svg?style=flat-square" alt="Licencia: GPL v3"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-brightgreen?style=flat-square" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-2.4-purple?style=flat-square" alt="Kotlin">
</p>
<p align="center">
  <a href="https://qm.qq.com/q/905451314"><img src="https://img.shields.io/badge/QQ群-905451314-0366CC?style=for-the-badge&logo=qq&logoColor=white" alt="Grupo de QQ"></a>
  <a href="https://museai.ltd"><img src="https://img.shields.io/badge/官网-museai.ltd-blueviolet?style=for-the-badge" alt="Sitio web"></a>
  <a href="https://museai.ltd/download/"><img src="https://img.shields.io/badge/Download-APK-brightgreen?style=for-the-badge&logo=android" alt="Descargar"></a>
</p>

<p align="center">
  <a href="#qué-es-muse">Qué es Muse</a> ·
  <a href="#capturas-de-pantalla">Capturas de pantalla</a> ·
  <a href="#descarga">Descarga</a> ·
  <a href="#primeros-pasos">Primeros pasos</a> ·
  <a href="#funciones">Funciones</a> ·
  <a href="用户手册.md">manual de usuario (chino)</a> ·
  <a href="#código-abierto">Código abierto</a>
</p>

---

## Qué es Muse

¿Tienes que presentarte desde cero cada vez que abres una conversación nueva con una IA? Con Muse no hace falta.

Muse es un compañero de IA de código abierto que corre en Android: **te recuerda de verdad** mediante un sistema de memoria de cuatro capas, **actúa dentro de tu teléfono** a través de tres canales de permisos (Accesibilidad / Shizuku / Root), y además tiene su propio mundo interior (Mood) y una vida propia: Momentos, diario, álbum. Cambias de modelo, cambias de sesión, cierras y vuelves a abrir: te sigue recordando. Y si pasa mucho tiempo sin hablar, toma la iniciativa y te busca.

Puedes cambiar de modelo a gusto (más de 40 proveedores predefinidos), sin registro y sin cuenta, y **por defecto todos los datos se quedan en tu dispositivo**. También puedes conectarlo a WeChat, QQ, Feishu, Telegram y DingTalk, para que el asistente viva dentro de las apps de chat que usas todos los días.

Todo está construido para continuar la conversación, no para empezar de cero.

## Capturas de pantalla

<p align="center">
  <img src="screenshots/en/chat.jpg" width="180" alt="Chat: conversación diaria con una tarjeta de Mood">
  <img src="screenshots/en/memory.jpg" width="180" alt="Memoria: flujo de hechos y línea de tiempo">
  <img src="screenshots/en/group-chat.jpg" width="180" alt="Chat grupal: varios asistentes conversando">
  <img src="screenshots/en/knowledge.jpg" width="180" alt="Base de conocimiento: ajustes de búsqueda">
</p>

<details>
<summary><b>Más capturas</b> (toca para expandir)</summary>

<p align="center">
  <img src="screenshots/en/automation.jpg" width="140" alt="Automatización de UI y asistente de permisos">
  <img src="screenshots/en/plugins.jpg" width="140" alt="Mercado de plugins y gestión de confianza">
  <img src="screenshots/en/channels.jpg" width="140" alt="Integración de canales de mensajería">
</p>
<p align="center">
  <img src="screenshots/en/assistants.jpg" width="140" alt="Gestión de asistentes e importación de tarjetas de personaje">
  <img src="screenshots/en/proactive.jpg" width="140" alt="Mensajes proactivos y horas de silencio">
  <img src="screenshots/en/web-search.jpg" width="140" alt="Ajustes de búsqueda web">
</p>
<p align="center">
  <img src="screenshots/en/data-backup.jpg" width="140" alt="Estadísticas, copia de seguridad y servidor web">
  <img src="screenshots/en/vision.jpg" width="140" alt="Asistencia visual y prueba de diagnóstico">
  <img src="screenshots/en/stickers.jpg" width="140" alt="Gestión de la biblioteca de stickers">
</p>

</details>

## Descarga

| Tu dispositivo | Elige | Notas |
| --- | --- | --- |
| Teléfonos recientes | `arm64-v8a` | Recomendado, el paquete más pequeño |
| Teléfonos más antiguos | `armeabi-v7a` | Para equipos de generaciones anteriores |
| Si no estás seguro | `universal` | Paquete universal, compatible con todo |

Descarga el APK más reciente desde la página oficial [museai.ltd/download](https://museai.ltd/download/) (conexión directa y rápida en China, es la opción recomendada); también está disponible en [GitHub Releases](https://github.com/Zer0Qing/Muse/releases/latest). Instálalo encima de la versión anterior y tus datos se conservan.

> [!WARNING]
> Descarga los paquetes solo desde la página oficial de Releases o desde el sitio web oficial. Los paquetes de canales desconocidos pueden estar modificados y poner en riesgo tus datos y tu dispositivo.

> [!NOTE]
> Las actualizaciones dentro de la app solo abren los archivos oficiales servidos por HTTPS de GitHub; nunca descargan ni instalan APKs en silencio. El aviso de "origen desconocido" al instalar es normal.

## Primeros pasos

1. **Configura un proveedor de modelos**: abre **Ajustes → Modelos y razonamiento → Proveedores**, elige un proveedor predefinido (DeepSeek, Qwen, Zhipu y otros regalan crédito al registrarte), pega tu API Key, prueba la conexión y obtén la lista de modelos.
2. **Empieza a conversar**: vuelve al inicio y envía tu primer mensaje. La memoria empieza a acumularse desde ese momento; cambia de modelo o de sesión y te seguirá recordando.
3. **Desbloquea capacidades del sistema (opcional)**: si quieres que "actúe", abre **Ajustes → Asistente de permisos** y activa los canales que necesites entre Accesibilidad / Shizuku / Root / Termux; cada paso incluye detección y pruebas integradas.

> La guía de uso completa (con todos los gestos y las operaciones menos visibles) está en el [manual de usuario (chino)](用户手册.md) o dentro de la app en **Ajustes → Tutorial**.

## Por qué Muse

### 1. Memoria persistente: nunca vuelve a presentarse

Una arquitectura de memoria en cuatro capas, que avanza desde la conversación a corto plazo hasta el procesamiento profundo a largo plazo:

```
conversación --> extracción de hechos --> resumen acumulativo --> compilación --> procesamiento profundo
corto plazo      datos clave              compresión              dedup/fusión    comprensión profunda
```

- **Los hechos críticos no se desvanecen nunca** (salud, finanzas e identidad central están protegidos), mientras la información cotidiana se olvida de forma natural, como en una persona
- Cada recuerdo se puede rastrear hasta su origen; el panel de memoria permite añadir y borrar entradas, ajustar el peso, filtrar y buscar, y expandir las entradas largas
- Los recuerdos se gestionan aislados por espacio; las copias de seguridad se cifran de extremo a extremo al cambiar de dispositivo

### 2. Capacidad de acción a nivel de sistema: de verdad hace cosas dentro de tu teléfono

| Canal | Capacidades | Requisito |
|------|------|------|
| Accesibilidad (proceso independiente) | Leer la pantalla, tocar / deslizar / mantener pulsado / escribir, Atrás / Inicio / panel de notificaciones | Activar un interruptor del sistema |
| Shell (Shizuku / adb) | Capturas de pantalla, comandos `input`, consultar la app en primer plano, comandos de dispositivo | Autorizar una vez |
| Root | Control total del sistema, acceso a los datos de otras apps | Dispositivo con root |
| Termux | Entorno Linux completo (apt / pip / gcc / ffmpeg) | Instalar Termux y autorizar |

- **Más de 200 herramientas integradas**, expuestas por capas por defecto; las capacidades de cola larga se cargan a demanda con `find_tools`
- Cada herramienta indica la autorización que requiere y su estado de preparación; si no está lista, **falla rápido antes de ejecutarse** y da instrucciones claras
- Terminal integrada (PTY propio + xterm), **pantalla virtual** en segundo plano, **GUI Agent** guiado por visión, automatización de navegador
- Límites de seguridad: lista blanca de comandos, aprobación por riesgo (Confianza / Consultar / Estricto), tiempos de espera duros, sandbox del espacio de trabajo

### 3. Personalidad y equipo: tiene mundo interior, y también vida

- **Mood en cuatro dimensiones**: Vibe / Sparks / Reflections / Will antes de cada respuesta, plegado por defecto; ábrelo para ver "cómo lo pensó"
- **Persona en tres capas**: capa de persona / capa de relación / capa de estilo, con las variables de plantilla `{{user_name}}` / `{{char}}`
- **Multiagente**: delega con `@nombre-del-asistente`, tarjetas de tarea con el estado paso a paso, subagentes en ventanas flotantes, bandeja de mensajes privados entre asistentes
- **Reuniones de grupo**: tarjeta de resumen / votaciones / observadores / plantillas rápidas / controles de reunión (pausar, saltar): una reunión formal entre varios asistentes
- **Hitos compartidos**: el primer mensaje, los días 7 / 30 / 100 juntos, los mensajes 100 / 1000... todo queda registrado por ti

## Funciones

<details>
<summary><b>Experiencia de chat</b>: streaming y continuidad / sesiones / acciones sobre mensajes / multimodelo / voz / multimodal / búsqueda</summary>

- **Streaming y tolerancia a fallos**: salida palabra por palabra vía SSE, con parada en cualquier momento; si el flujo se corta, se reanuda solo (sigue escribiendo cuando vuelve la red). El stream-guard integrado detecta el "fin prematuro del stream" (stream vacío o sin primer paquete) y reintenta sin streaming; un watchdog de primer evento (45 s en modelos normales, 90 s en modelos de razonamiento sin primer paquete) cambia el modo de petición automáticamente, así los modelos lentos dejan de dejarte esperando frente a una pantalla en blanco.
- **Gestión de sesiones**: fijar / archivar / borrado con deshacer / agrupar en carpetas / reordenar las fijadas arrastrando; búsqueda global con índice de texto completo de SQLite FTS5 (con retroceso automático a FTS4 en entornos compatibles); cada sesión tiene su propia ventana de contexto y las conversaciones muy largas se comprimen con resúmenes acumulativos.
- **Acciones sobre mensajes**: regenerar conservando varias versiones (conmutador `1/3`, sin perder el historial); ramificar (abrir una línea nueva desde cualquier mensaje del usuario, la original se conserva intacta); responder citando (el texto citado se inyecta en el contexto); anotaciones en mensajes; menú de pulsación larga con copiar (texto plano / Markdown), reenviar, compartir, guardar en favoritos, editar, traducir, leer en voz alta y eliminar.
- **Favoritos**: gestión centralizada, con etiquetas de grupo al mantener pulsado.
- **Experiencia de entrada**: entrada a pantalla completa (para textos largos), comandos de barra (`/new` `/compact` `/reset` `/pin` `/archive`), pegar como archivo (umbral configurable), mensajes rápidos; una llamada de herramienta interrumpida se puede reanudar o descartar con un toque.
- **Captura rápida**: cápsula flotante (opcional), notas rápidas desde cualquier pantalla, guardadas en la biblioteca local de notas.
- **Razonamiento profundo**: niveles OFF / AUTO / LOW / MEDIUM / HIGH / XHIGH (presupuesto de pensamiento de 1000 a 16000 tokens), con el proceso de razonamiento plegado.
- **Multimodelo**: soporte nativo de los protocolos de OpenAI / Anthropic / Gemini y de cualquier endpoint compatible con OpenAI; más de 40 proveedores predefinidos con lista de modelos obtenida dinámicamente; matriz de capacidades por modelo (visión / razonamiento / herramientas); las tareas auxiliares van por niveles separados de modelo "herramienta pequeña / herramienta grande" (títulos, compresión, resúmenes y compilación de memoria); cabeceras y cuerpo de petición personalizados, y proxy de red.
- **Voz**: reconocimiento de voz en streaming (transcripción mientras hablas, visualización de la onda, deslizar hacia arriba para cancelar); TTS con voces del sistema o voces en la nube (OpenAI / MiniMax / Edge y otros), configurado por asistente; clonación de voz en la nube (ElevenLabs / FishAudio).
- **Entrada multimodal**: varias imágenes por mensaje; análisis automático de PDF / DOCX / EPUB / TXT; OCR offline para documentos escaneados.
- **Asistencia visual**: al enviar imágenes a un modelo sin visión, se llama automáticamente a un modelo de visión que genera una descripción estructurada en ocho dimensiones (resumen general / texto OCR / objetos y disposición / datos de gráficos / reformulación de la petición / respuesta a la petición / evidencia visual / incertidumbre), con grounding por coordenadas; las descripciones se cachean por "imagen + petición + versión del prompt"; análisis concurrente de varias imágenes, tiempo límite de 60 segundos y degradación automática a una descripción textual si falla.
- **Búsqueda y navegación web**: fuentes de búsqueda configurables (búsqueda nativa del modelo / Bing / Baidu / SearXNG / Tavily / DeepSeek / motores personalizados) con respaldo en cascada; un motor personalizado puede apuntar a cualquier servicio de búsqueda que devuelva JSON (un SearXNG propio, una pasarela interna), con GET / POST, mapeo de rutas de los campos del resultado y autenticación Bearer opcional, y se coloca al principio de la cadena Auto; la extracción del cuerpo de las páginas usa Jina Reader; navegador integrado por sesión, con una cápsula superior que se expande en imagen en vivo mientras la IA navega.
- **Traducción y lectura en voz alta**: traduce o lee cualquier mensaje con una pulsación larga; el modelo de traducción se configura por separado; se conserva el historial de traducciones.
- **Renderizado y tarjetas**: renderizado completo de Markdown (resaltado de código para más de 20 lenguajes, fórmulas KaTeX, diagramas Mermaid, tablas); la IA puede emitir tarjetas interactivas y tarjetas de artefactos (código / documentos / tablas), gestionadas en un centro de artefactos con vista a pantalla completa y exportación.

</details>

<details>
<summary><b>Memoria y crecimiento</b>: pipeline de cuatro capas / decaimiento y protección / continuidad del contexto / biblioteca de experiencias / base de conocimiento / mensajes proactivos</summary>

- **Pipeline de memoria en cuatro capas**: (1) el flujo original de la conversación; (2) extracción de hechos (se extraen automáticamente los hechos clave, con su importancia y su origen); (3) resúmenes acumulativos (las conversaciones largas se comprimen sin perder el hilo); (4) procesamiento profundo (compilación en segundo plano, deduplicación y fusión hasta formar memoria a largo plazo). Los hechos se indexan a texto completo y son consultables.
- **Importancia y decaimiento**: los hechos críticos (salud, finanzas, identidad central) están protegidos y no decaen; la información cotidiana se olvida según su frecuencia de uso, como en una persona.
- **Panel de memoria**: navegación por espacio o categoría; añadir y borrar entradas, ajustar la importancia, filtrar por fecha o por peso; pulsación larga para seleccionar varias y borrar en lote (tanto en la lista como en la línea de tiempo, sin volver al principio al terminar); cada recuerdo se puede rastrear hasta la sesión de origen; las entradas largas se expanden al tocarlas.
- **Espacios de memoria**: los recuerdos de distintos propósitos quedan aislados y no se contaminan entre sí.
- **Continuidad del contexto**: el historial que queda fuera de la ventana de contexto ya no se descarta en silencio: se genera un extracto determinista del historial (conservando fragmentos literales de la conversación y entradas de herramientas) que se inserta delante de la ventana; al comprimir los turnos de herramientas se conservan "las notas del asistente"; el resumen de compresión indica "qué archivos se tocaron en este tramo"; las tareas largas ya no pierden la memoria.
- **Continuidad entre sesiones**: una conversación nueva puede traer los últimos turnos de la anterior (entre 4 y 40, ajustable, desactivado por defecto), para retomar el mismo tema sin volver a explicar el contexto.
- **Biblioteca de experiencias**: convierte "este tipo de problema se resuelve así" en experiencia reutilizable que las tareas futuras consultan automáticamente (se puede activar o desactivar).
- **Base de conocimiento (RAG)**: los documentos importados se analizan, se fragmentan y se indexan; la recuperación combina búsqueda semántica y coincidencia de palabras clave; con @ se limita la búsqueda a documentos concretos; los archivos muy grandes se indexan en streaming; en entornos sin conexión se degrada a búsqueda local; las citas remiten a los fragmentos originales.
- **Resumen diario y saludos**: a la hora configurada se genera un repaso del día (visible en el inicio, con notificación opcional); el saludo de la mañana lo genera el modelo a partir de los asuntos recientes en la memoria, no es una plantilla fija.
- **Mensajes proactivos**: no hay sondeos a intervalos fijos. Funciona con un "libro de lo que te quiere decir": el asistente anota ahí lo que te quiere preguntar y elige una entrada cuando llega el momento. Que lo abras, respondas o lo descartes queda en un libro de resultados, y de ahí aprende la intimidad para decidir cuántas veces puede hablar hoy. Cinco niveles (Silencioso / Reservado / Estándar / Activo / Pegajoso), con la opción de volver al nivel por defecto y poner a cero la cuota del día; se respetan las horas de silencio; si no tiene nada que aportar, prefiere no escribir; y hay una red de seguridad (si llevan mucho tiempo sin hablar, saluda por iniciativa propia). De madrugada sigue escribiendo su diario y ordenando recuerdos sin molestarte; el contenido de las notificaciones diarias es configurable (resumen diario / diario nocturno / novedades de Momentos).
- **Hitos**: el primer mensaje, los días 7 / 30 / 100 juntos, los mensajes 100 / 1000 y otros momentos se registran automáticamente y se pueden revisar en la página de hitos.
- **Estadísticas y panel de actividad**: la página de estadísticas incluye el total de conversaciones y mensajes, la media diaria, la racha de días y un mapa de calor diario; el panel de actividad muestra el historial de ejecución de tareas programadas, heartbeats y compilación de memoria.

</details>

<details>
<summary><b>Herramientas y automatización</b>: más de 200 herramientas / capas de permisos / aprobaciones y presupuestos / cuatro canales / terminal / pantalla virtual / GUI Agent</summary>

- **Sistema de herramientas**: más de 200 herramientas integradas, expuestas por capas (núcleo / comunes / opcionales / avanzadas); por defecto solo se envían las comunes para mantener el contexto ligero, y las capacidades de cola larga se buscan y cargan con `find_tools` por palabra clave (una vez cargadas siguen disponibles durante la sesión); el prompt del sistema incluye un índice vivo de capacidades, con el número realmente disponible en cada ronda y no una constante escrita a mano.
- **Capas de permisos**: cada herramienta que necesita Shizuku / Root, Accesibilidad o Termux declara sus dependencias y su estado (READY / NOT READY); si no está lista, falla rápido antes de ejecutarse y devuelve un error estructurado `permission_required` con instrucciones de autorización.
- **Sistema de aprobación**: nivel de riesgo (seguro / normal / alto) combinado con tres modos (Confianza / Consultar / Estricto); se admite "permitir en esta sesión" y políticas personalizadas por herramienta; las acciones de alto riesgo se preguntan antes de ejecutarse.
- **Motor de ejecución**: las herramientas de solo lectura se ejecutan en paralelo de forma controlada (máximo 3) para ganar velocidad; las herramientas bloqueantes usan un pool de hilos dedicado con tiempos de espera duros; las salidas demasiado grandes se guardan en disco y en el contexto solo queda una vista previa con una referencia para `read_file`; criterio unificado de éxito o fallo; parada temprana automática tras fallos repetidos, indicando el motivo real; las rondas de herramientas no tienen límite por defecto (se puede fijar un máximo), y hay protecciones de circuito por llamadas idénticas repetidas, rondas sin progreso y número total de llamadas.
- **Cuatro canales de ejecución**: Accesibilidad (proveedor en proceso independiente y comunicación AIDL, que sobrevive a las actualizaciones de la app) / Shizuku (elevación por Binder) / Root (su) / Termux (RUN_COMMAND): el nivel se elige automáticamente según lo que esté disponible.
- **Comandos de dispositivo**: `device_shell` pasa por una lista blanca de verbos (dumpsys / settings / am / pm / input / screencap / uiautomator / cmd / svc / wm / getprop / content, etc.); las tuberías, las redirecciones y el encadenamiento están prohibidos, y todo lo que quede fuera de la lista blanca se rechaza.
- **Terminal integrada**: motor PTY propio con página de terminal xterm; tres entornos: la terminal del sandbox de la app, el canal de comandos del dispositivo y el Linux completo de Termux (apt / pip / gcc / ffmpeg).
- **Pantalla virtual**: un proceso servidor independiente crea una pantalla oculta en segundo plano, de modo que todo el flujo de operaciones del agente no ocupa tu pantalla principal; los fotogramas se capturan a demanda.
- **GUI Agent**: bucle de varios pasos guiado por visión: captura de pantalla → decisión del modelo de visión → tocar / deslizar / escribir → nueva captura para verificar, hasta completar la tarea.
- **Automatización de navegador**: navigate / click / type / extract / scroll / get_html / snapshot; una instancia de navegador independiente por sesión.
- **Espacio de trabajo**: espacio de archivos controlado (leer / escribir / listar / mover / borrar), con las operaciones limitadas a los límites del sandbox.
- **Programaciones y recordatorios**: tareas programadas con expresiones cron e historial de ejecución; alarmas, temporizadores, eventos de calendario y notas que se ejecutan o avisan a su hora.
- **Escucha de notificaciones**: lee el flujo de notificaciones del dispositivo y responde o hace sugerencias de forma inteligente.
- **Asistente de permisos**: detección y guía de los cuatro canales en un solo lugar, con una prueba en vivo de "leer pantalla".

</details>

<details>
<summary><b>Asistentes y ecosistema</b>: reuniones de grupo / world book / Skills / mercado de plugins / MCP / cinco canales de mensajería</summary>

- **Varios asistentes**: cada asistente configura su propio modelo / prompt de sistema / parámetros de muestreo / recursos vinculados / lista blanca de herramientas / skills; delega con `@nombre` y la tarjeta de tarea muestra el estado de cada paso; los subagentes ejecutan tareas largas en ventanas flotantes sin ocupar la conversación principal; los asistentes tienen una bandeja de mensajes privados propia.
- **Asistentes avanzados**: world book (entradas permanentes + disparadores por palabra clave + inyección por profundidad); inyecciones de prompt (fragmentos personalizados según el modo); recursos asociados (vincular una base de conocimiento para priorizar la recuperación); importación y exportación: se admiten tarjetas de personaje de SillyTavern, y tus propios asistentes se pueden empaquetar para compartirlos.
- **Reuniones de grupo**: cuatro modos de discusión (por turnos / libre / debate / con moderador); antes de cada ronda se lee el contexto del grupo; se puede saltar el turno; memoria aislada por asistente. Capacidades de reunión: tarjeta dedicada de resumen de la discusión (copiar / compartir / guardar como documento compartido), renderizado de votos, miembros observadores, documentos compartidos del grupo y contexto privado de la IA, control de la cola de palabra (pausar / continuar / saltar), plantillas rápidas (revisión de propuestas / debate a favor y en contra / lluvia de ideas / retrospectiva de proyecto), plegado de rondas, y las interrupciones durante un turno no se pierden.
- **Sistema de Skills**: las skills integradas funcionan sin configurar nada; importación y exportación de `.skill.json` (con esquema de parámetros); las skills de prompt aceptan parámetros de entrada (marcadores `{{input}}` / `{{args}}`); la instalación pasa por listas blancas de implementación y protección contra inyección.
- **Mercado de plugins**: buscar / instalar / activar / desactivar / desinstalar con gestión de confianza; los plugins pueden aportar herramientas, paneles de UI, skins para las burbujas de mensaje y más; el asistente puede escribir borradores de plugins en JS, que solo surten efecto después de firmarlos y activarlos manualmente; los plugins se ejecutan dentro de los límites del sandbox.
- **Protocolo MCP**: conecta MCP Servers externos para ampliar el conjunto de herramientas (autenticación OAuth / transporte SSE / descubrimiento automático).
- **Conectores OAuth**: integra servicios de terceros con credenciales cifradas en local; el centro de conexiones gestiona modelos, canales y conectores en un solo lugar.
- **Cinco canales de mensajería**: WeChat (vinculación por QR) / QQ (bot por WebSocket) / Feishu (eventos por conexión persistente) / Telegram / DingTalk (modo Stream); cada contacto tiene su propio contexto de sesión y su resumen acumulativo; las imágenes y los mensajes de voz se guardan y se transcriben automáticamente; cada canal puede vincularse a un asistente y un modelo distintos; cada plataforma trae su propio asistente de configuración y prueba de conexión.
- **Servidor web**: servidor Ktor integrado (autenticación JWT + descubrimiento mDNS + certificado HTTPS autofirmado) para abrir las conversaciones desde el navegador de cualquier dispositivo de la red local.

</details>

<details>
<summary><b>Mini teléfono y Momentos</b> (versión experimental temprana, en mejora continua): IM simulado / Momentos de la IA / álbum / diario</summary>

- **Mini teléfono**: cliente de mensajería instantánea simulado e integrado, con la estructura de cuatro pestañas (Chats / Contactos / Descubrir / Yo); puedes cambiar el fondo de pantalla y ajustar opciones, y usar la IA como si fuera una app de mensajería.
- **Momentos de la IA**: la IA publica novedades (texto e imágenes, incluida la cuadrícula de nueve fotos), da me gusta y comenta; centro de mensajes, portada y página de perfil.
- **Álbum y diario de la IA**: las imágenes generadas se archivan (con fecha y vista a pantalla completa); el diario se consulta por calendario mensual, y de madrugada la IA escribe el suyo.
- **Clima y notas rápidas**: fuente de datos meteorológicos gratuita; las notas rápidas se guardan en la pestaña "Yo".

</details>

<details>
<summary><b>Interfaz y apariencia</b>: temas / skins de burbujas / iconos / idiomas / copia de seguridad y datos</summary>

- **Sistema de temas**: 12 temas completos (cada uno con modo claro y oscuro) + 8 paletas accesibles para daltonismo; color dinámico (Android 12+), cambio programado día / noche y temas personalizados.
- **Burbujas y apariencia**: radio de las esquinas de las burbujas ajustable; sistema de skins de burbujas, con skins extendidas que aportan los plugins (por ejemplo alto contraste o brillo suave).
- **Iconos dibujados a mano**: 176 iconos propios (rejilla de 24dp, trazo redondeado de 1.7dp, adaptación a claro y oscuro), con código abierto en [muse-icons](https://github.com/Zer0Qing/muse-icons) (MIT).
- **Tipografía e idiomas**: fuente / tamaño / espaciado entre letras, botón atrás predictivo, adaptación de accesibilidad; la interfaz está disponible en chino / inglés / japonés / coreano / español / portugués / ruso.
- **Copia de seguridad y restauración**: exportación local con un toque (JSON / NDJSON por bloques, con cifrado opcional); copia automática diaria (paquete con instantáneas de muse.db / memory.db / facts.db); sincronización en la nube con S3 / WebDAV; el libro de lo que te quiere decir de los mensajes proactivos, el libro de resultados y la intimidad de la relación se incluyen en la copia; antes de restaurar se guarda una copia de protección y, si falla, se revierte automáticamente.
- **Importación de datos**: migración con un toque desde CherryStudio / Chatbox / ChatGPT / RikkaHub / Kelivo; importación de historiales de conversación en varios formatos.
- **Biblioteca de stickers**: importación de archivos zip con estructura de carpetas y categorización automática; tres niveles de frecuencia de envío (ocasional / normal / frecuente); borrado por lotes y vaciado.
- **Widget de pantalla de inicio**: crear una conversación con un toque; menú rápido al mantener pulsado el icono de la app.
- **Centro de cuenta**: avatar y apodo se gestionan en local, sin registro ni inicio de sesión: no hay cuenta que se pueda "perder".
- **Registro de auditoría y proxy**: las llamadas de herramientas y las acciones clave quedan registradas; se admite proxy de red personalizado.

</details>

<details>
<summary><b>Seguridad y privacidad</b>: todo en local / cifrado / enmascarado de PII / límites del sandbox</summary>

- **Todo en local**: conversaciones, memorias y bases de conocimiento se guardan en una base de datos Room local; sin telemetría, sin analítica y sin recogida de datos; sin registro ni inicio de sesión; las funciones de red están desactivadas por defecto.
- **Enmascarado automático de PII**: los números de teléfono, documentos de identidad y tarjetas bancarias se sustituyen por marcadores antes de enviar, y los marcadores se restauran en la respuesta del modelo: la información real no llega al proveedor.
- **Canales cifrados**: la configuración sensible usa Android Keystore (AES-256-GCM); las contraseñas de las copias en la nube usan PBKDF2 + AES-256-GCM.
- **Protección de enlaces**: confirmación antes de abrir un enlace; la salida se sanea antes de renderizarse en WebView (sin iframe / form / pseudoprotocolos).
- **Límites de las herramientas**: lista blanca de comandos, aprobación por riesgo, tiempos de espera duros, verificación previa de permisos y devolución honesta del motivo del fallo; los plugins se ejecutan en sandbox por defecto.
- **Registros de fallos**: solo se guardan en local y se pueden exportar manualmente en modo seguro.

</details>

## Stack técnico y arquitectura

| Área | Tecnología |
|------|------|
| Lenguaje / UI | Kotlin 2.4 · Jetpack Compose · Material 3 |
| Arquitectura | MVVM + flujo de datos unidireccional · Koin (DI) |
| Datos | Room (SQLite) + DataStore · ONNX Runtime (embeddings locales) |
| Red | OkHttp + Ktor (servidor web con JWT + mDNS + HTTPS) |
| Análisis y renderizado | PDFBox · ML Kit OCR · PTY propio + xterm |
| Controles de calidad | detekt + ktlint + Android Lint + scripts de verificación propios · cobertura con Kover |

```
:app                        App principal (UI / sesiones / orquestación de herramientas / canales / ajustes)
:ai                         Capa de abstracción de proveedores (OpenAI / Anthropic / Gemini, streaming y llamadas de herramientas)
:memory                     Motor de memoria (almacén de hechos / compilación / resúmenes)
:accessibility(+provider)   Servicio de accesibilidad y proveedor en proceso independiente (sobrevive a las actualizaciones de la app)
:terminal                   Canal de Termux y terminal
:virtual-display-*          Servidor y protocolo de pantalla virtual
:common                     Logging, tipos de resultado y utilidades compartidas
```

## Compilar desde el código fuente

Requisitos: JDK 21 y Android SDK (platform 36, build-tools 36.0.0).

```bash
git clone https://github.com/Zer0Qing/Muse.git
cd Muse

# Compilación de depuración
./gradlew :app:assembleDebug

# Instalar en un dispositivo conectado
./gradlew :app:installDebug
```

Las compilaciones de release exigen indicar la versión de forma explícita y aportar la firma (si falta cualquiera de las dos, las restricciones duras de la compilación lo bloquean):

```powershell
./gradlew :app:assembleRelease "-PversionName=<x.y.z>" "-PversionCode=<número>"
# Firma: en la raíz del proyecto debe existir keystore.properties (storeFile / storePassword / keyAlias / keyPassword)

# Verificación previa a la publicación (consistencia de firma en todos los APK por ABI)
python ci/script/validate_release_apks.py --apk "app/build/outputs/apk/release/*.apk"
```

## Código abierto

Las contribuciones son bienvenidas:

- [Guía de contribución](CONTRIBUTING.md) · [Política de seguridad](SECURITY.md) · [Normas de mantenimiento de la biblioteca de componentes de UI](UI组件库维护规范.md)
- Antes de abrir un PR, pasa los scripts de verificación locales y los tests unitarios; cada cadena nueva debe sincronizarse en todos los paquetes de idioma
- Los scripts de verificación y del pipeline de publicación están en `ci/script/`; el tutorial integrado en la app (Ajustes → Tutorial) se mantiene en paralelo

## Apoya el proyecto

Muse lo mantiene un desarrollador independiente: es gratuito, de código abierto, sin compras dentro de la app y sin sistema de cuentas. Si te resulta útil, puedes apoyar voluntariamente su desarrollo continuo:

**Invita un café al autor** (QR de WeChat / Alipay):

<p align="center">
  <img src="art/support/wechat-pay.png" width="200" alt="Código QR de cobro de WeChat">
  <img src="art/support/alipay.jpg" width="200" alt="Código QR de cobro de Alipay">
</p>

**Únete a los grupos de la comunidad** (con gusto te leemos):

<p align="center">
  <img src="art/support/wechat-group.jpg" width="200" alt="Código QR del grupo de WeChat">
  <img src="art/support/qq-group.jpg" width="200" alt="Código QR del grupo de QQ">
</p>

> El apoyo es totalmente voluntario y no está vinculado a ninguna función, cuota o actualización; no apoyar no afecta al uso normal de Muse.

## Sobre el autor

El proyecto lo mantiene un desarrollador independiente en Chengdu. Si tu equipo está contratando, o simplemente quieres conversar, escríbeme directamente.

- Ubicación: Chengdu
- Buscando: prácticas de ingeniería de software
- Contacto: los grupos de arriba, o un Issue en el repositorio

## Licencia

El proyecto usa la **GNU General Public License v3** (GPL v3). El texto completo de la licencia está en [LICENSE](LICENSE), y la lista de licencias de dependencias de terceros en [NOTICE](NOTICE).
