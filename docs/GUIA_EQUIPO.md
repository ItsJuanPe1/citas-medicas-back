# Guía del equipo: cómo continuar el proyecto

Sistema de citas médicas (Oracle + PL/SQL, Spring Boot, React). Esta guía explica cómo está armado y cómo agregar su HU sin romper lo que ya funciona.

**Antes de escribir código, lean las secciones 1, 2 y 3.**

## 1. Estado actual

| Listo | Detalle |
|---|---|
| Login con roles | Token JWT (8 horas). La contraseña se guarda con SHA-256. |
| HU01 Reservar cita | `PKG_GESTION_CITAS.RESERVAR_CITA`: bloqueo `FOR UPDATE`, sin cruces de horario. |
| HU02 Cancelar cita | `PKG_GESTION_CITAS.CANCELAR_CITA`: motivo obligatorio, libera el horario. |
| Auditoría | Las reservas y cancelaciones quedan en `LOG_AUDITORIAS`. |
| Pantallas | Reservar cita, Citas, Médicos, Todas las citas, Auditoría, Mi agenda (médico). |

**Pendiente (reparto en la sección 8):** HU03 a HU10.

## 2. Reglas que no se rompen

1. **La base de datos está congelada.** Las tablas y los datos de prueba no se modifican (nada de `ALTER TABLE` ni cambiar los inserts). Si una HU necesita algo que no cabe, primero se habla en el grupo y se abre un issue. Se pueden crear paquetes, triggers, vistas, índices y procedimientos.
2. **La lógica de negocio vive en PL/SQL** (paquetes y triggers). El back solo llama al procedimiento y el front solo muestra.
3. **Quién es el usuario sale del token**, nunca del body. En el back: `@AuthenticationPrincipal AuthUser user` (`idUsuario`, `rol`, `idEntidad`).
4. **El SUPERADMIN puede hacer todo.** Siempre se incluye en los permisos de cada módulo.
5. **Estados:** nunca escribir el id de `PARAMETRICA_TIPOS` a mano. Se busca por `NOMBRE` + `VALOR` (ver la función `id_param` en `pkg_gestion_citas.sql`).
6. **Ids:** siempre con la secuencia `SEQ_<TABLA>.NEXTVAL`.
7. **Cada procedimiento** hace su `COMMIT` al final y `ROLLBACK` + `RAISE` si falla.
8. **Auditoría:** las acciones importantes se registran en `LOG_AUDITORIAS` con `VALOR_ANTES` y `VALOR_DESPUES`.
9. **Paquetes con estos nombres:** `PKG_GESTION_CITAS` (HU01, 02, 09), `PKG_HISTORIAS_CLINICAS` (HU03, 04, 07, 08), `PKG_GESTION_MEDICAMENTOS` (HU05, 06).

## 3. Cómo correr todo

Orden: **BD → back → front.**

1. **BD** (repo db): `01_esquema` → `02_tablas` → `03_datos` → `04_secuencias` → `06_procedimientos/*.sql`.
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
| FARMACEUTICO | `monica.duque`, `hector.becerra` | `ID_FARMACEUTICO` | Inicio (provisional) |

Más usuarios: `SELECT USERNAME, ID_ROL, ID_ENTIDAD FROM USUARIOS;`

## 4. Estructura del proyecto

**Back** (`src/main/java/.../`)
- `config/`: `SecurityConfig` (permisos y CORS) y `OracleErrorHandler` (errores de PL/SQL → HTTP).
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
| `POST /api/citas` | paciente, recepción, superadmin | HU01: reservar |
| `GET /api/citas?idPaciente=` | paciente, recepción, superadmin | Citas de un paciente |
| `PUT /api/citas/{id}/cancelar` (body `{motivo}`) | paciente, recepción, superadmin | HU02: cancelar |
| `GET /api/pacientes?q=` | recepción, superadmin | Buscar paciente |
| `GET /api/admin/citas` | superadmin, gerente | Todas las citas con filtros |
| `GET /api/admin/auditoria` | superadmin | Últimos 100 movimientos |
| `GET /api/medico/citas?historial=` | médico | Agenda del médico (base de HU03) |

## 6. Receta para agregar una HU

### a) Base de datos
1. Crear o ampliar el paquete en `06_procedimientos/pkg_<nombre>.sql` (copiar la estructura de `pkg_gestion_citas.sql`).
2. Errores de negocio con `RAISE_APPLICATION_ERROR(-20xxx, 'mensaje claro')` dentro del rango del paquete (**el mensaje sin tildes**, para evitar problemas de codificación):
   - citas: 20001–20099
   - historias clínicas: 20100–20199
   - medicamentos y farmacia: 20200–20299
3. Si hay concurrencia, `SELECT ... FOR UPDATE WAIT 5` (ver HU01).
4. Dejar un script de prueba en `tests/` (ver `hu01_prueba.sql`, `hu02_prueba.sql`).

### b) Back
1. Crear el controller copiando `CitaController`. Para llamar a un procedimiento:
   ```java
   jdbc.execute((ConnectionCallback<Void>) con -> {
       try (CallableStatement cs = con.prepareCall("{call PKG_X.MI_PROC(?,?)}")) {
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
| Auditoría | SUPERADMIN |
| Agenda del médico y historias clínicas (HU03, 04) | MEDICO (solo sus pacientes), SUPERADMIN |
| Recetas y farmacia (HU05, 06) | MEDICO (prescribe), FARMACEUTICO (entrega), SUPERADMIN |

## 8. Reparto sugerido

| Quién | HU | Notas |
|---|---|---|
| Persona 1 | HU03 + HU04 | `PKG_HISTORIAS_CLINICAS` + trigger BEFORE INSERT. Se parte de `MiAgenda.jsx`: agregar el botón "Registrar consulta" en cada cita PROGRAMADA. |
| Persona 2 | HU05 + HU06 | `PKG_GESTION_MEDICAMENTOS` + trigger de stock. Reemplaza la pantalla provisional `Inicio.jsx` del farmacéutico. |
| Persona 3 | HU07 + HU08 | VPD/RLS y trigger de auditoría sobre `HISTORIA_CLINICA`. El trigger se puede empezar ya. |
| Persona 4 | HU09 + HU10 | Cancelación masiva (BULK COLLECT/FORALL) en `PKG_GESTION_CITAS`, y reporte para el gerente. |

HU05 necesita la historia clínica de HU03: coordinar para que la Persona 1 suba primero la creación de historias, aunque sea sin pantalla.

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