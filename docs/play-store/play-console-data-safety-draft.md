# Play Console Data Safety Draft - Asombrate

Fecha: 2026-04-25

## Respuestas técnicas recomendadas

- Datos recopilados: Sí, limitados a funcionalidad principal.
- Datos compartidos o vendidos para publicidad: No.
- Cifrado en tránsito: Sí.
- Cuenta obligatoria: No.

## Categorías

| Categoría | ¿Se recopila? | Motivo | ¿Obligatoria? |
| --- | --- | --- | --- |
| Ubicación precisa | Sí, opcional | Ruta y recomendación | Solo si el usuario la pide |
| Ubicación aproximada | Sí, opcional | Ruta y recomendación | Solo si el usuario la pide |
| Información personal | No | N/A | N/A |
| Actividad para analytics | No | N/A | N/A |
| IDs publicitarios | No | N/A | N/A |
| Información financiera | No | N/A | N/A |

## Notas para Play Console

- No hay cuenta ni perfil persistente.
- No hay SDK de anuncios ni analytics.
- No se guarda historial persistente de ubicación.
- Release usa HTTPS y no embebe `ORS_API_KEY`.
