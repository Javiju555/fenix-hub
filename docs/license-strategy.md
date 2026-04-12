# License strategy (AGPL vs GPL)

## Objetivo

Evitar privatizacion facil del trabajo OSS de FenixHub y obligar retorno de mejoras.

## Recomendacion

Usar AGPL-3.0-only como licencia principal.

## Por que AGPL y no solo GPL

- GPLv3 obliga compartir codigo cuando distribuyes binarios.
- AGPLv3 tambien obliga compartir codigo cuando ofreces el software por red como servicio.

Si el riesgo es que alguien monte una variante cerrada como servicio y no publique cambios, AGPL cubre mejor ese caso.

## Trade-offs reales

- AGPL es mas dura para adopcion comercial cerrada.
- Compatibilidad con algunas distribuciones de tiendas y ecosistemas cerrados puede ser mas compleja.
- Si en el futuro se quiere maximizar adopcion corporativa cerrada, se puede evaluar dual-licensing.

## Decision actual

- Licencia elegida: AGPL-3.0-only.
- SPDX en manifests actualizado.
- Archivo LICENSE agregado en raiz.
