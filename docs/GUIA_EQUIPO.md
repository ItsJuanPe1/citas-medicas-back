# Guía del equipo: cómo continuar el proyecto

Sistema de citas médicas (Oracle + PL/SQL, Spring Boot, React). Esta guía explica cómo está armado y cómo agregar su HU sin romper lo que ya funciona.

**Antes de escribir código, lean las secciones 1, 2 y 3.**

## 1. Estado actual

| Listo | Detalle |
|---|---|
| Login con roles | Token JWT (8 horas). La contraseña se guarda con SHA-256. Los usuarios nuevos entran con clave temporal y deben cambiarla. |
| HU01 Reservar cita | `PKG_GESTION_CITAS.PRO_RESERVAR_CITA`: bloqueo `FOR UPDATE` sobre la agenda; **cada cita dura exactamente 1 hora**, dentro del rango de la agenda; sin cruces del médico ni del paciente. |
| HU02 Cancelar cita | `PRO_CANCELAR_CITA`: sin motivo obligatorio; la cita queda CANCELADA y su agenda vuelve a DISPONIBLE. |
| HU03 / HU04 Consulta | `PKG_HISTORIAS_CLINICAS.PRO_REGISTRAR_CONSULTA` (solo desde la hora de la cita) y `TRG_HISTORIA_CITA_PROGRAMADA`. Al guardar el diagnóstico se abre la receta en la misma fila. |
| HU05 Recetas y farmacia | `PKG_GESTION_MEDICAMENTOS`: recetar (hasta 24 horas después de la consulta), entregar (parcial si falta stock) y reponer stock. `TRG_DETALLE_ESTADO_RECETA` calcula el estado de la receta. |
| HU06 Stock mínimo | `TRG_STOCK_MINIMO`: al bajar el stock por debajo del mínimo deja `ALERTA_STOCK_MINIMO` en la bitácora (usuario Sistema). Farmacia avisa en pantalla y hay una pantalla Stock con reposición. |
| HU07 Control de acceso | `PKG_SEGURIDAD` + política `POL_HISTORIA_CLINICA` (`DBMS_RLS`): el médico solo lee historias de sus pacientes, el paciente las suyas, recepción ninguna. Roles de Oracle `ROLE_MEDICO` y `ROLE_RECEPCION`. |
| Auditoría por triggers | Historias, recetas, detalle de receta, stock y medicamentos quedan auditados por triggers (`TRG_AUD_*` y `TRG_HISTORIA_AUDITORIA`), aunque el cambio se haga con SQL directo. |
| Auditoría centralizada | `PKG_AUDITORIA.PRO_REGISTRAR` es el único que escribe en `TBL_LOG_AUDITORIAS`; los triggers y el horario lo llaman. Compilarlo antes que los triggers. |
| Reprogramación por cambio de horario | Al guardar un horario que quita agendas con citas, `PRO_REPROGRAMAR_AGENDA` pasa las citas al siguiente día libre (misma hora si se puede) y deja la explicación visible al paciente; sin hueco en 60 días, cancela explicando. |
| HU08 Auditoría de historias | `TRG_HISTORIA_AUDITORIA`: cada cambio de diagnóstico o notas, o eliminación, queda en la bitácora con el valor anterior y el nuevo, el usuario de la aplicación y el usuario Oracle. |
| Horarios, usuarios, perfil | Horario semanal del médico (`PKG_AGENDAS`, 52 semanas, sin tabla nueva), alta de personal y pacientes (`PKG_USUARIOS`), perfil (`PKG_PERFILES`), estado NO_ASISTIO (job). |

| HU09 Cancelación masiva | `PRO_CANCELAR_CITAS_MEDICO`: cancela las citas programadas de un médico en una fecha (`BULK COLLECT` / `FORALL`), deja trazabilidad por cita y bloquea las agendas de ese día. Pantalla "Cancelación por inasistencia" (solo SUPERADMIN) con vista previa y confirmación. |
| HU10 Reporte | `PKG_REPORTES.PRO_REPORTE_PERIODO`: citas por estado y recetas emitidas en un periodo, más el detalle por médico (cursores explícitos y `SYS_REFCURSOR`). Pantalla "Reporte" para GERENTE y SUPERADMIN, con descarga CSV. |

**Las 10 HU están hechas.** Lo que queda es probar y revisar (sección 8).

## 2. Reglas que no se rompen

1. **Si cambias una tabla**, edita `02_tablas/creacion_tablas.sql` (única fuente de verdad), avisa al grupo y vuelve a exportar `datos_companieros.sql`. No uses `ALTER TABLE` sueltos.
2. **La lógica de negocio vive en PL/SQL** (paquetes y triggers). El back solo llama al procedimiento y el front solo muestra.
3. **Quién es el usuario sale del token**, nunca del body. En el back: `@AuthenticationPrincipal AuthUser user` (`idUsuario`, `rol`, `idEntidad`).
4. **El SUPERADMIN puede hacer todo.** Siempre se incluye en los permisos de cada módulo.
5. **Estados:** nunca escribir el id de `TBL_PARAMETRICA_TIPOS` a mano. Se busca por `NOMBRE` + `VALOR` con la función privada `FN_ID_PARAM` de cada paquete.
6. **Ids:** siempre con la secuencia `SEQ_TBL_<TABLA>.NEXTVAL`.
7. **Cada procedimiento** hace su `COMMIT` al final y `ROLLBACK` + `RAISE` si falla.
8. **Auditoría:** las acciones importantes se registran en `TBL_LOG_AUDITORIAS` con `VALOR_ANTES` y `VALOR_DESPUES` (usuario 0 = sistema).
9. **Paquetes:** `PKG_GESTION_CITAS`, `PKG_AGENDAS`, `PKG_HISTORIAS_CLINICAS`, `PKG_GESTION_MEDICAMENTOS`, `PKG_PERFILES`, `PKG_USUARIOS`, `PKG_SEGURIDAD`, `PKG_REPORTES`.
10. **Nombres estándar (obligatorio en todo código nuevo):**

| Objeto | Prefijo | Ejemplo |
|---|---|---|
| Tabla / Trigger / Secuencia / Índice / Paquete | `TBL_` / `TRG_` / `SEQ_TBL_` / `IDX_` / `PKG_` | `TBL_CITAS_MEDICAS`, `TRG_STOCK_MINIMO` |
| Procedimiento / Función | `PRO_` / `FN_` | `PRO_RESERVAR_CITA`, `FN_ID_PARAM` |
| Variable: numérica, texto, fecha, hora (timestamp), registro, booleana, colección | `vn_` `vs_` `vd_` `vt_` `vr_` `vb_` `vc_` | `vn_total`, `vs_estado` |
| Parámetro: numérico, texto, fecha, hora | `pn_` `ps_` `pd_` `pt_` | `pn_id_cita` |
| Constante / Excepción | `cn_` `cs_` / `ex_` o `EX_` | `EX_SIN_STOCK` |

   Las llamadas desde Java llevan el prefijo: `{call PKG_GESTION_CITAS.PRO_RESERVAR_CITA(?,...)}`.

## 3. Cómo correr todo

Orden: **BD → back → front.**

1. **BD** (repo db): sigue la tabla de `db/README_db.md` en orden (esquema, tablas, datos, secuencias, triggers, paquetes, horarios iniciales y seguridad). La seguridad (HU07) necesita un paso como `SYSTEM`.
2. **Back:** crear `.env` en la raíz con:
   ```
   DB_PASSWORD=PROYECTOPLSQL
   JWT_SECRET=<cualquier texto de mínimo 32 caracteres, cada uno el suyo>
   ```
   Correr `bootRun` (puerto 8080).
3. **Front:** crear `.env` con `VITE_API_URL=http://localhost:8080/api`. Correr `npm install` y `npm run dev`.

Los `.env` están en `.gitignore`: **nunca se suben a GitHub.**

Si el front sale en un puerto distinto de 5173 o 5174, hay que agregarlo en `SecurityConfig` (CORS).

### Usuarios de prueba (clave de todos: `Clave123`)

| Rol | Usuario | `idEntidad` (token) | Llega a |
|---|---|---|---|
| SUPERADMIN | `superadmin1` | — | Todas las citas |
| GERENTE | `gerente1` | — | Todas las citas |
| RECEPCIONISTA | `recepcionista1`, `recepcionista2` | — | Citas |
| MEDICO | `dr.torres`, `dr.cardona`, … | `ID_MEDICO` | Mi agenda |
| PACIENTE | `valentina1`, `jorge2`, … | `ID_PACIENTE` | Mis citas |
| FARMACEUTICO | `monica.duque`, `hector.becerra` | `ID_FARMACEUTICO` | Farmacia |

Más usuarios: `SELECT USERNAME, ID_ROL, ID_ENTIDAD FROM TBL_USUARIOS;`

## 4. Estructura del proyecto

**Back** (`src/main/java/.../`)
- `config/`: `SecurityConfig` (permisos y CORS), `OracleErrorHandler` (errores de PL/SQL → HTTP) y `ContextoSeguridadConfig` (envía rol, entidad y usuario a Oracle en cada conexión, para HU07 y HU08).
- `jobs/`: tareas programadas (`AgendasSemanalesJob`, `NoAsistioJob`).
- `security/`: `JwtService`, `JwtAuthFilter`, `AuthUser`.
- `controller/`: un controller por módulo (`CitaController`, `AgendaController`, `AdminController`, `MedicoCitasController`…). **`CitaController` es la plantilla de referencia.**

**Front** (`src/`)
- `api.js`: toda llamada al back pasa por `api("/ruta")` (agrega el token y maneja el 401).
- `auth/`: `AuthContext` (sesión), `ProtectedRoute` (rutas por rol) y **`roles.js`** (grupos de roles y pantalla de inicio de cada rol).
- `components/`: `Shell` (barra y menú), `PacientePicker`, `toast` / `ToastProvider`.
- `pages/`: una pantalla por archivo. **`Citas.jsx` y `MiAgenda.jsx` son las plantillas de referencia.**
- `index.css`: todos los estilos y variables de color.

## 5. Endpoints existentes

| Método y ruta | Roles | Para qué |
|---|---|---|
| `POST /api/auth/login` | público | Iniciar sesión |
| `GET /api/health` | público | Verificar que el back responde |
| `GET /api/medicos` | paciente, recepción, superadmin, gerente | Lista de médicos |
| `GET /api/agendas/disponibles?idMedico=` | paciente, recepción, superadmin | Horarios libres |
| `POST /api/citas` (body `{idAgenda, inicio, fin, idPaciente?}`) | paciente, recepción, superadmin | HU01: reservar una hora (la cita dura 1 hora) dentro de una agenda |
| `GET /api/citas?idPaciente=` | paciente, recepción, superadmin | Citas de un paciente |
| `PUT /api/citas/{id}/cancelar` | paciente, recepción, superadmin | HU02: cancelar (sin motivo) |
| `GET /api/pacientes?q=` | recepción, superadmin | Buscar paciente |
| `GET /api/admin/citas` | superadmin, gerente | Todas las citas con filtros |
| `GET /api/admin/auditoria` | superadmin | Últimos 100 movimientos |
| `POST /api/historias` | médico, superadmin | HU03: registra la consulta (diagnóstico + notas) y deja la cita ATENDIDA |
| `GET/POST/DELETE /api/horario` | médico, superadmin | Agendas (rangos de horas) del médico (`PKG_AGENDAS`) |
| `GET/PUT /api/perfil` | cualquier usuario | Ver perfil y editar teléfono/correo (`PKG_PERFILES`) |
| `GET /api/medicamentos` | médico, farmacéutico, superadmin | Catálogo con stock |
| `POST /api/recetas` | médico, superadmin | HU05: prescribe la receta de una historia |
| `GET /api/farmacia/pendientes` | farmacéutico, superadmin | HU05: medicamentos por entregar |
| `POST /api/farmacia/entregas/{idDetalle}` | farmacéutico, superadmin | HU05: entrega y descuenta stock (parcial si falta) |
| `GET /api/medico/citas?historial=` | médico | Agenda del médico, incluye diagnóstico y notas de citas atendidas |
| `GET /api/farmacia/stock?q=` | farmacéutico, superadmin | HU06: stock de cada medicamento (los de bajo mínimo primero) |
| `POST /api/farmacia/stock/{idMedicamento}/reposicion` (body `{cantidad}`) | farmacéutico, superadmin | Repone stock |
| `GET/PUT /api/horario/semanal` | médico, superadmin | Horario semanal que se repite |
| `/api/admin/usuarios` | superadmin | Alta de personal, estado y restablecer clave |
| `GET /api/admin/citas/cancelacion-masiva/previa?idMedico=&fecha=` | superadmin | HU09: citas que se cancelarían |
| `POST /api/admin/citas/cancelacion-masiva` (body `{idMedico, fecha, motivo?}`) | superadmin | HU09: cancela y bloquea la agenda del día |
| `GET /api/reportes/periodo?desde=&hasta=` | gerente, superadmin | HU10: resumen del periodo y detalle por médico |

## 6. Receta para agregar una HU

### a) Base de datos
1. Crear o ampliar el paquete en `06_procedimientos/pkg_<nombre>.sql` (copiar la estructura de `pkg_gestion_citas.sql`). Respetar los nombres de la regla 10.
2. Errores de negocio con `RAISE_APPLICATION_ERROR(-20xxx, 'mensaje claro')` dentro del rango del paquete (**el mensaje sin tildes**, para evitar problemas de codificación):
   - citas: 20001–20099
   - historias clínicas: 20100–20199
   - medicamentos y farmacia: 20200–20299
   - agendas: 20300–20399, perfiles: 20400–20499, usuarios: 20500–20599, reportes: 20600–20699
3. Si hay concurrencia, `SELECT ... FOR UPDATE WAIT 5` (ver HU01).
4. Dejar un script de prueba en `tests/` (ver `hu01_prueba.sql`, `hu02_prueba.sql`).

### b) Back
1. Crear el controller copiando `CitaController`. Para llamar a un procedimiento:
   ```java
   jdbc.execute((ConnectionCallback<Void>) con -> {
       try (CallableStatement cs = con.prepareCall("{call PKG_X.PRO_MI_PROC(?,?)}")) {
           cs.setLong(1, ...);
           cs.setLong(2, user.idUsuario());
           cs.execute();
           return null;
       }
   });
   ```
2. **No atrapar** los errores `ORA-200xx`: `OracleErrorHandler` los convierte en `{"mensaje": "..."}`. Si un código necesita un estado HTTP distinto de 400 (409 conflicto, 404 no existe, 403 prohibido), agregarlo en su tabla `ESTADOS`.
3. Agregar los permisos en `SecurityConfig`, en la sección "Reglas por módulo".

### c) Front
1. Crear la página en `pages/` copiando la estructura de `Citas.jsx`: estado `null` = cargando (con skeleton), estado vacío y error.
2. Usar siempre `api("/ruta")`.
3. Definir el grupo de roles en `auth/roles.js`.
4. Registrar la ruta en `App.jsx` con `<ProtectedRoute roles={...}>`.
5. Agregar el enlace en `ENLACES` de `Shell.jsx`.
6. Si es la pantalla principal de un rol, cambiarla en `INICIO` de `roles.js`.
7. Avisos al usuario con el toast, no con alertas dentro de la página:
   ```jsx
   import { useToast } from "../components/toast";
   const toast = useToast();
   toast.ok("Guardado");   // toast.error("No se pudo guardar")
   ```
8. Estilos: usar las clases de `index.css` (`.table-wrap`, `.badge`, `.btn`, `.field`, `.state`…) y las variables de color (`--accent`, `--danger`…). No inventar colores.

## 7. Permisos por módulo

| Módulo | Roles |
|---|---|
| Reservar, ver y cancelar citas | PACIENTE (solo las suyas), RECEPCIONISTA, SUPERADMIN |
| Todas las citas | SUPERADMIN, GERENTE |
| Cancelación masiva (HU09) | SUPERADMIN |
| Reporte de atención y prescripción (HU10) | GERENTE, SUPERADMIN |
| Auditoría | SUPERADMIN |
| Agenda del médico y historias clínicas (HU03, 04) | MEDICO (solo sus pacientes), SUPERADMIN |
| Recetas y farmacia (HU05, 06) | MEDICO (prescribe), FARMACEUTICO (entrega y stock), SUPERADMIN |
| Historias clínicas en la base (HU07) | La política RLS filtra por rol: MEDICO sus pacientes, PACIENTE las suyas, RECEPCIONISTA ninguna; el resto todas |

## 8. Reparto sugerido

HU01 a HU10 están hechas. Lo que sigue es de todo el grupo:

| Qué | Notas |
|---|---|
| Pruebas cruzadas | Cada persona prueba las HU de otra con al menos dos roles distintos. |
| Revisión de nombres | Revisar la regla 10 (prefijos) en el código de cada HU antes de cerrar los PR. |
| Documentación del proyecto | Mantener `README_db.md` y esta guía al día cuando algo cambie. |

## 9. Git

- Las ramas `feature/HU03-registro-consulta` salen de `develop`. El PR va a `develop`. `main` solo recibe merges desde `develop`.
- El PR menciona su issue: `Closes OWNER/citas-medicas-db#3` (o el repo que corresponda).
- Mover la tarjeta en el tablero: *In Progress* al empezar y *In Review* al abrir el PR.
- Cada HU se prueba con al menos dos roles distintos antes de pedir revisión.

**Checklist del PR**
- [ ] El paquete compila sin errores en Oracle.
- [ ] El back arranca sin errores.
- [ ] `npm run build` y `npx oxlint src` pasan sin errores.
- [ ] El SUPERADMIN tiene acceso al módulo.
- [ ] Hay un script de prueba en `tests/`.
- [ ] Los errores de negocio muestran un mensaje claro (no "Error interno del servidor").

## 10. Problemas conocidos

| Síntoma | Causa |
|---|---|
| `ORA-01017` al arrancar el back | El `.env` no se lee: revisar el nombre, la ubicación y la línea `spring.config.import` |
| Puerto 8080 ocupado | Queda un proceso viejo del back |
| Error de CORS en el navegador | El front corre en un puerto que no está en `SecurityConfig` |
| 401 en todo | Token vencido (8 h) o el back se reinició con otro `JWT_SECRET`: volver a iniciar sesión |
| "Error interno del servidor" | Es un error que no es de negocio. Mirar la consola del back: busca la línea `Error de base de datos` |
| 403 donde debería haber un 500 | Spring Security oculta errores en `/error`; mirar el log del back |
| Paquete inválido o `PLS-` al compilar | Revisar el `SHOW ERRORS` que sale al final del script |
| `/api/medicos` responde 403 | Ese usuario (médico o farmacéutico) no tiene permiso; es intencional |
| `PLS-00302: component ... must be declared` al llamar un procedimiento | El Java llama al nombre viejo; falta el prefijo `PRO_` o `FN_` |
| `ORA-01031` al correr `seguridad_dba.sql` | Hay que correrlo como `SYSTEM`, no como `PROYECTOPLSQL` |
| El médico ve historias de otros o el back no abre conexiones | Falta la política (`rls_historias.sql`) o `PKG_SEGURIDAD`; ver el orden del README de la BD |
| La auditoría muestra `#1` en vez de Sistema | Falta el `NVL(... 'Sistema')` en la consulta de `AdminController` |


## Modelo cita-agenda (1 a N)
Una agenda es un rango de horas del médico y puede tener varias citas; cada cita guarda su `FECHA_HORA_INICIO` y `FECHA_HORA_FIN` y **dura exactamente 1 hora**. HU09 lee la hora de la cita en `TBL_CITAS_MEDICAS`, no en la agenda.
