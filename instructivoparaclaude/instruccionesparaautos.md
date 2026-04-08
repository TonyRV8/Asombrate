Notas de construcción del SVG para tu código

1. Idea general

No construyas el vehículo como una imagen fija “decorada”.
Constrúyelo como un plano modular:

contorno del vehículo
zonas internas
asientos individuales
espacios vacíos como pasillo
opcionalmente ventanas o cabina

La pieza más importante son los asientos, porque son los que vas a pintar dinámicamente.

2. Estructura recomendada del SVG

Organízalo por grupos:

Dentro de seats, cada asiento debe ir separado:

<g id="seat-1A">...</g>
<g id="seat-1B">...</g>
<g id="seat-2A">...</g>

Eso te permite cambiar color, hover, selección y estado con código.

3. Filosofía de diseño

Hazlo como si fuera un selector de asientos, no como una ilustración realista.

Eso implica:

formas simples
geometría clara
mucha legibilidad
poco detalle
distribución entendible de inmediato 4. Colores base

Usa una base neutra para que luego el estado solar destaque.

Sugerencia:

vehículo / piso: gris muy claro
líneas secundarias: gris claro
asientos inactivos: gris oscuro

Y luego por lógica:

sol: naranja / ámbar
sombra: gris oscuro o azul grisáceo
parcial: amarillo tenue
seleccionado: el color de UI que quieras 5. Cómo construir el layout

Primero define la geometría, luego el estilo.

Orden recomendado
dibuja el contorno del vehículo
marca la parte frontal
reserva el pasillo
coloca los asientos
agrupa y nombra cada asiento
prueba recoloreado por código 6. Cómo pensar los asientos

No dibujes cada asiento desde cero.
Diseña un solo asiento universal y repítelo.

Eso te da:

consistencia visual
menos trabajo
facilidad para escalar a otros vehículos
mejor mantenimiento 7. Cómo guardar la información

Además del SVG, conviene guardar un layout en datos.

Ejemplo conceptual:

const seats = [
{ id: "1A", x: 80, y: 60, zone: "left-window" },
{ id: "1B", x: 130, y: 60, zone: "left-inner" },
{ id: "1C", x: 240, y: 60, zone: "right-inner" },
{ id: "1D", x: 290, y: 60, zone: "right-window" }
];

Así separas:

visual = SVG
lógica = datos

Eso es mucho mejor para tu app.

8. Relación entre SVG y código

Tu algoritmo no debería “pintar una imagen”.
Debería cambiar el estado de cada asiento.

Por ejemplo:

sun
shade
partial
neutral

Y luego renderizar color según ese estado.

9. Qué evitar

No te conviene:

usar PNG/JPG como base principal
meter demasiados detalles
hacer trazos complejos difíciles de editar
depender de una ilustración distinta por vehículo sin sistema común 10. Enfoque ideal del proyecto

Tu sistema debería tener dos capas:

Capa visual

Un plano SVG minimalista del vehículo.

Capa lógica

Un conjunto de estados calculados por tu algoritmo solar.

La app une ambas:
algoritmo decide estado → SVG refleja color.

11. Resultado final esperado

Cada vehículo debe verse como parte de la misma familia visual:

mismo tipo de asiento
mismo grosor de líneas
misma paleta
misma simplicidad
misma lógica de layout

Así aunque cambies de autobús a combi o taxi, el usuario siente que todo pertenece a la misma app.
