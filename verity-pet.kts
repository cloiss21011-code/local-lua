import kotlin.math.*
import nursultan.math.*

name("Verity Pet")
description("3D питомец Verity: реалистичные дискретные прыжки с наклоном, плавное парение и Look-at")

// ═══════════════════════════════════════════════════════════════════════════════
//  НАСТРОЙКИ
// ═══════════════════════════════════════════════════════════════════════════════
val petSize    by slider("Размер", 0.45f, 0.15f, 1.5f, 0.05f)
val moveMode   = selectable("Режим", "Прыжки", "Парение", selected = "Прыжки")
val followDist by slider("Дистанция", 1.8f, 0.8f, 4.0f, 0.1f)

// ═══════════════════════════════════════════════════════════════════════════════
//  GPU ШЕЙДЕР ДЛЯ ГЛАДКОЙ СФЕРЫ (Аппаратный Smooth Shading)
// ═══════════════════════════════════════════════════════════════════════════════
val sphereShader = shader("verity-smooth-sphere", """
    #version 330
    in vec3 v_norm;
    out vec4 out_color;
    uniform vec3 u_lightDir;

    void main() {
        vec3 n = normalize(v_norm);
        float dotL = dot(n, normalize(u_lightDir));
        float wrap = dotL * 0.5 + 0.5;
        float diff = 0.52 + 0.48 * (wrap * wrap);
        vec3 col = vec3(1.0, 0.865, 0.08) * diff;
        out_color = vec4(col, 1.0);
    }
""", """
    #version 330
    layout(location = 0) in vec3 pos;
    layout(location = 1) in vec3 norm;
    uniform mat4 u_view;
    uniform mat4 u_projection;
    out vec3 v_norm;

    void main() {
        v_norm = norm;
        gl_Position = u_projection * u_view * vec4(pos, 1.0);
    }
""")

val sphereFormat = gpu.format(VertexAttribute.floats(3), VertexAttribute.floats(3))
val sphereMesh = gpu.indexedMesh(sphereFormat)
val spherePipeline = gpu.pipeline(sphereShader, DrawMode.TRIANGLES, BlendMode.ALPHA, DepthMode.TEST_AND_WRITE)
val spherePass = gpu.renderType(spherePipeline, sphereMesh)

// ═══════════════════════════════════════════════════════════════════════════════
//  СОСТОЯНИЕ И ДИСКРЕТНАЯ ФИЗИКА ПРЫЖКОВ
// ═══════════════════════════════════════════════════════════════════════════════
var baseX = 0.0
var baseY = 0.0
var baseZ = 0.0
var prevBaseX = 0.0
var prevBaseY = 0.0
var prevBaseZ = 0.0

// Фазы дискретного прыжка (толчок -> полет -> приземление -> пауза)
var isJumping = false
var jumpProgress = 0.0
var prevJumpProgress = 0.0
var hopStartX = 0.0
var hopStartY = 0.0
var hopStartZ = 0.0
var hopEndX = 0.0
var hopEndY = 0.0
var hopEndZ = 0.0
var jumpCooldown = 0
var squishTicks = 0

// Скорость в режиме парения
var hoverVelX = 0.0
var hoverVelY = 0.0
var hoverVelZ = 0.0

var currentYaw = 0.0
var previousYaw = 0.0
var currentPitch = 0.0
var previousPitch = 0.0

var anchorYaw = 0.0
var lastPlayerX = 0.0
var lastPlayerZ = 0.0
var playerKeyInput = false
var isInitialized = false
var tickCount = 0L

fun resetPet() {
    isInitialized = false
    isJumping = false
    jumpProgress = 0.0
    prevJumpProgress = 0.0
    jumpCooldown = 0
    squishTicks = 0
    hoverVelX = 0.0
    hoverVelY = 0.0
    hoverVelZ = 0.0
    hopStartX = 0.0
    hopStartY = 0.0
    hopStartZ = 0.0
    hopEndX = 0.0
    hopEndY = 0.0
    hopEndZ = 0.0
}

onEnable { resetPet() }
onDisable { resetPet() }
on<WorldLoadEvent> { resetPet() }

on<MoveInputEvent> { e ->
    playerKeyInput = e.forward() || e.backward() || e.left() || e.right() || e.jump()
}

fun wrapDegrees(degrees: Double): Double {
    var d = degrees % 360.0
    if (d >= 180.0) d -= 360.0
    if (d < -180.0) d += 360.0
    return d
}

fun findGroundLevel(x: Double, startY: Double, z: Double): Double {
    val bx = floor(x).toInt()
    val bz = floor(z).toInt()
    var by = floor(startY + 2.0).toInt().coerceAtMost(319)
    val minY = floor(startY - 16.0).toInt().coerceAtLeast(-64)
    while (by > minY && world.block(bx, by, bz).air()) {
        by--
    }
    return by + 1.0
}

fun pushOutOfWalls(pos: Vec, r: Double, groundY: Double): Vec {
    var px = pos.x()
    var pz = pos.z()
    val bottomY = max(pos.y() - r * 0.7, groundY + 0.05)
    val topY = pos.y() + r * 0.88
    if (topY <= bottomY) return pos
    val box = Box(px - r * 0.88, bottomY, pz - r * 0.88, px + r * 0.88, topY, pz + r * 0.88)
    val collisions = world.blockCollisionsIn(box)

    for (c in collisions) {
        val overlapX = if (px < c.center().x()) (px + r * 0.88) - c.minX() else (px - r * 0.88) - c.maxX()
        val overlapZ = if (pz < c.center().z()) (pz + r * 0.88) - c.minZ() else (pz - r * 0.88) - c.maxZ()
        val absX = abs(overlapX)
        val absZ = abs(overlapZ)

        if (absX < absZ && absX > 0.001) {
            px -= overlapX
        } else if (absZ > 0.001) {
            pz -= overlapZ
        }
    }
    return Vec(px, pos.y(), pz)
}

// ═══════════════════════════════════════════════════════════════════════════════
//  ЛОГИКА ОБНОВЛЕНИЯ (ClientTickEvent)
// ═══════════════════════════════════════════════════════════════════════════════
on<ClientTickEvent> {
    if (!inGame) {
        resetPet()
        return@on
    }

    tickCount++
    val pPos = player.position()
    val pEye = player.eyePosition()
    val R = petSize.toDouble()
    val d = followDist.toDouble()
    val isJumpMode = moveMode.value().name() == "Прыжки"

    // ─────────────────────────────────────────────────────────────────────────
    // 1. ДЕТЕКЦИЯ ДВИЖЕНИЯ ИГРОКА И СТАБИЛЬНЫЙ ЯКОРЬ
    // ─────────────────────────────────────────────────────────────────────────
    val movedX = pPos.x() - lastPlayerX
    val movedZ = pPos.z() - lastPlayerZ
    val moveDist = sqrt(movedX * movedX + movedZ * movedZ)
    lastPlayerX = pPos.x()
    lastPlayerZ = pPos.z()

    val isPlayerMoving = playerKeyInput || moveDist > 0.035

    if (!isInitialized || pPos.distanceTo(Vec(baseX, baseY, baseZ)) > 26.0) {
        anchorYaw = player.rotation().yawDegrees().toDouble()
        val rad = Math.toRadians(anchorYaw)
        val fwdX = -sin(rad)
        val fwdZ = cos(rad)
        baseX = pPos.x() - fwdX * d
        baseZ = pPos.z() - fwdZ * d
        baseY = findGroundLevel(baseX, pPos.y(), baseZ) + R
        prevBaseX = baseX
        prevBaseY = baseY
        prevBaseZ = baseZ
        currentYaw = anchorYaw
        previousYaw = currentYaw
        currentPitch = 0.0
        previousPitch = 0.0
        isJumping = false
        jumpProgress = 0.0
        prevJumpProgress = 0.0
        jumpCooldown = 0
        isInitialized = true
        return@on
    }

    // Якорный угол обновляется только при реальном шаге игрока по миру
    if (moveDist > 0.035) {
        val moveYaw = Math.toDegrees(atan2(-movedX, movedZ))
        val yDiff = wrapDegrees(moveYaw - anchorYaw)
        anchorYaw += yDiff * 0.16
    }

    val anchorRad = Math.toRadians(anchorYaw)
    val fwdX = -sin(anchorRad)
    val fwdZ = cos(anchorRad)
    val rightX = -fwdZ
    val rightZ = fwdX

    var targetX = pPos.x() - fwdX * (d * 0.75) + rightX * (d * 0.45)
    var targetZ = pPos.z() - fwdZ * (d * 0.75) + rightZ * (d * 0.45)
    val groundAtTarget = findGroundLevel(targetX, pPos.y(), targetZ)

    var targetY = if (isJumpMode) {
        groundAtTarget + R
    } else {
        val hoverHeight = 1.15 + (if (player.height() < 1.65) -0.35 else 0.0)
        max(pPos.y() + hoverHeight, groundAtTarget + hoverHeight * 0.75)
    }

    // Рейкаст видимости: не прятаться за стены
    val rayToTarget = raycast.blocks(pEye, Vec(targetX, targetY, targetZ), null, null)
    if (rayToTarget.isBlock()) {
        val hit = rayToTarget.position()
        val dir = pEye.subtract(Vec(targetX, targetY, targetZ)).normalize()
        val safe = hit.add(dir.multiply(R + 0.25))
        targetX = safe.x()
        targetZ = safe.z()
    }
    val safeTargetGround = findGroundLevel(targetX, pPos.y(), targetZ) + R
    val resolvedTarget = pushOutOfWalls(Vec(targetX, safeTargetGround, targetZ), R, safeTargetGround)
    targetX = resolvedTarget.x()
    targetZ = resolvedTarget.z()

    prevBaseX = baseX
    prevBaseY = baseY
    prevBaseZ = baseZ
    prevJumpProgress = jumpProgress
    previousYaw = currentYaw
    previousPitch = currentPitch

    if (squishTicks > 0) squishTicks--

    val toTargetX = targetX - baseX
    val toTargetZ = targetZ - baseZ
    val horizDist = sqrt(toTargetX * toTargetX + toTargetZ * toTargetZ)

    // ─────────────────────────────────────────────────────────────────────────
    // 2. ВЫСОКИЕ УПРУГИЕ ПРЫЖКИ И ДИНАМИЧЕСКАЯ СКОРОСТЬ
    // ─────────────────────────────────────────────────────────────────────────
    if (isJumpMode) {
        if (isJumping) {
            // ПИТОМЕЦ В ПРЫЖКЕ: ПРЫЖОК ОБЯЗАН ДОЙТИ ДО КОНЦА (НЕ СБРАСЫВАТЬСЯ В ВОЗДУХЕ!)
            val hopDuration = when {
                horizDist > 4.0 -> 7.8
                horizDist > 2.0 -> 8.6
                else -> 9.4
            }
            jumpProgress += 1.0 / hopDuration

            if (jumpProgress >= 1.0) {
                // Приземление в конце траектории
                jumpProgress = 1.0
                isJumping = false
                baseX = hopEndX
                baseY = hopEndY
                baseZ = hopEndZ
                squishTicks = 2
                // Если игрок уже остановился — сразу сбрасываем стейт и не начинаем новый прыжок!
                jumpCooldown = if (!isPlayerMoving) 0 else (if (horizDist > 2.5) 1 else 2)
            } else {
                // В полете плавно летим по дуге к hopEndX, hopEndY, hopEndZ
                val p = jumpProgress.coerceIn(0.0, 1.0)
                val t = (1.0 - cos(p * PI)) * 0.5
                baseX = hopStartX + (hopEndX - hopStartX) * t
                baseY = hopStartY + (hopEndY - hopStartY) * t
                baseZ = hopStartZ + (hopEndZ - hopStartZ) * t

                // Если игрок бежит во время нашего полета — аккуратно подправляем конечную точку
                if (isPlayerMoving) {
                    hopEndX += (targetX - hopEndX) * 0.35
                    hopEndY += (targetY - hopEndY) * 0.35
                    hopEndZ += (targetZ - hopEndZ) * 0.35
                }
            }
        } else {
            // ПИТОМЕЦ НА ЗЕМЛЕ (НЕ В ПРЫЖКЕ)
            jumpProgress = 0.0
            prevJumpProgress = 0.0
            if (jumpCooldown > 0) jumpCooldown--

            val groundY = findGroundLevel(baseX, baseY, baseZ) + R
            baseY += (groundY - baseY) * 0.50

            if (!isPlayerMoving && horizDist < 0.75) {
                // Игрок стоит на месте и питомец уже приземлился рядом:
                // Спокойно стоит на месте, не прыгая и не дергаясь!
                baseX += toTargetX * 0.15
                baseZ += toTargetZ * 0.15
            } else {
                // Игрок идет/бежит или питомец отстал — запускаем новый прыжок!
                val shouldStartHop = jumpCooldown == 0 && ((isPlayerMoving && horizDist > 0.26) || horizDist > 1.4)

                if (shouldStartHop) {
                    isJumping = true
                    jumpProgress = 0.0
                    prevJumpProgress = 0.0
                    hopStartX = baseX
                    hopStartY = baseY
                    hopStartZ = baseZ

                    // Дальность прыжка масштабируется прямо под дистанцию до игрока (до 20 блоков)
                    val leapDist = horizDist.coerceAtMost(20.0)
                    val dirX = toTargetX / horizDist
                    val dirZ = toTargetZ / horizDist
                    val landX = baseX + dirX * leapDist
                    val landZ = baseZ + dirZ * leapDist
                    val landGroundY = findGroundLevel(landX, pPos.y(), landZ) + R

                    // Проверяем точку приземления, чтобы не приземлиться в блок
                    val resolvedLand = pushOutOfWalls(Vec(landX, landGroundY, landZ), R, landGroundY)
                    hopEndX = resolvedLand.x()
                    hopEndY = landGroundY
                    hopEndZ = resolvedLand.z()
                }
            }
        }
    } else {
        // Режим "Парение": динамическая скорость с быстрым откликом
        isJumping = false
        jumpProgress = 0.0
        prevJumpProgress = 0.0

        val flightRay = raycast.blocks(Vec(baseX, baseY, baseZ), Vec(baseX + hoverVelX * 3.5, baseY + hoverVelY * 3.5, baseZ + hoverVelZ * 3.5), null, null)
        if (flightRay.isBlock()) {
            hoverVelY += 0.14
        }

        if (horizDist > 0.06) {
            val spring = if (horizDist > 3.0) 0.18 else 0.12
            val drag = 0.85
            hoverVelX = (hoverVelX + toTargetX * spring) * drag
            hoverVelY = (hoverVelY + (targetY - baseY) * spring) * drag
            hoverVelZ = (hoverVelZ + toTargetZ * spring) * drag

            val speed = sqrt(hoverVelX * hoverVelX + hoverVelY * hoverVelY + hoverVelZ * hoverVelZ)
            val maxSpeed = (0.85 + horizDist * 0.25).coerceAtMost(2.6)
            if (speed > maxSpeed) {
                hoverVelX = (hoverVelX / speed) * maxSpeed
                hoverVelY = (hoverVelY / speed) * maxSpeed
                hoverVelZ = (hoverVelZ / speed) * maxSpeed
            }
        } else {
            hoverVelX *= 0.5; hoverVelY *= 0.5; hoverVelZ *= 0.5
            if (abs(hoverVelX) < 0.005) hoverVelX = 0.0
            if (abs(hoverVelZ) < 0.005) hoverVelZ = 0.0
        }

        baseX += hoverVelX
        baseY += hoverVelY
        baseZ += hoverVelZ
    }

    val currentGroundY = findGroundLevel(baseX, baseY, baseZ) + R
    val resolved = pushOutOfWalls(Vec(baseX, baseY, baseZ), R, currentGroundY)
    baseX = resolved.x()
    baseZ = resolved.z()

    // ─────────────────────────────────────────────────────────────────────────
    // 3. КОНТЕКСТНЫЙ LOOK-AT И НАКЛОН (PITCH/ROLL TILT)
    // ─────────────────────────────────────────────────────────────────────────
    val targetLookYaw: Double
    val targetPitch: Double

    if (isJumpMode) {
        if (isJumping) {
            // В прыжке: смотрим strictly в сторону полета и наклоняемся вперед на 12-14 градусов
            val jumpDirX = hopEndX - hopStartX
            val jumpDirZ = hopEndZ - hopStartZ
            targetLookYaw = if (jumpDirX * jumpDirX + jumpDirZ * jumpDirZ > 0.001) {
                Math.toDegrees(atan2(jumpDirX, jumpDirZ))
            } else {
                currentYaw
            }
            // Наклон вперед по параболической дуге прыжка (до 13 градусов)
            targetPitch = sin(jumpProgress * PI) * 13.0
        } else {
            // На земле в покое: смотрим на игрока и стоим ровно
            val toEyeX = pEye.x() - baseX
            val toEyeZ = pEye.z() - baseZ
            targetLookYaw = Math.toDegrees(atan2(toEyeX, toEyeZ))
            targetPitch = 0.0
        }
    } else {
        val hoverSpeed = sqrt(hoverVelX * hoverVelX + hoverVelZ * hoverVelZ)
        if (hoverSpeed > 0.04 && horizDist > 0.30) {
            targetLookYaw = Math.toDegrees(atan2(hoverVelX, hoverVelZ))
            targetPitch = (hoverSpeed * 22.0).coerceAtMost(12.0)
        } else {
            val toEyeX = pEye.x() - baseX
            val toEyeZ = pEye.z() - baseZ
            targetLookYaw = Math.toDegrees(atan2(toEyeX, toEyeZ))
            targetPitch = 0.0
        }
    }

    val yDiff = wrapDegrees(targetLookYaw - currentYaw)
    val turnSpeed = if (isJumping) 0.28 else 0.12
    currentYaw += yDiff * turnSpeed

    val pDiff = targetPitch - currentPitch
    currentPitch += pDiff * 0.25
}

// ═══════════════════════════════════════════════════════════════════════════════
//  3D РЕНДЕР VERITY (Параболическая траектория + Наклон + Smooth Shading)
// ═══════════════════════════════════════════════════════════════════════════════
val lightDir = Vec(0.35, 0.8, 0.45).normalize()

on<Render3DEvent> { e ->
    if (!inGame || !isInitialized) return@on

    val render = e.render()
    val delta = e.tickDelta().toDouble()
    val cam = e.camera()

    val ix = prevBaseX + (baseX - prevBaseX) * delta
    val iy = prevBaseY + (baseY - prevBaseY) * delta
    val iz = prevBaseZ + (baseZ - prevBaseZ) * delta

    val isJumpMode = moveMode.value().name() == "Прыжки"
    val R = petSize.toDouble()

    // ─────────────────────────────────────────────────────────────────────────
    // 1. ПАРАБОЛИЧЕСКАЯ ДУГА ПРЫЖКА (Быстрый толчок -> Зависание -> Падение)
    // ─────────────────────────────────────────────────────────────────────────
    var currentJumpY = 0.0
    var squishY = 1.0

    if (isJumpMode) {
        val interpProgress = (prevJumpProgress + (jumpProgress - prevJumpProgress) * delta).coerceIn(0.0, 1.0)
        if (isJumping || interpProgress > 0.0) {
            val p = interpProgress
            // Высокая, плавная дуга, высота которой пропорциональна дальности прыжка
            val jumpArc = sin(p * PI)
            val leapDist = sqrt((hopEndX - hopStartX) * (hopEndX - hopStartX) + (hopEndZ - hopStartZ) * (hopEndZ - hopStartZ))
            val distBoost = (leapDist * 0.07).coerceIn(0.0, 0.85)
            val jumpHeight = (1.55 + distBoost) * (R / 0.45).coerceIn(1.15, 2.75)
            currentJumpY = jumpArc * jumpHeight

            // Мягкий упругий сквош/стретч (легкое вытягивание вверх при взлете, сжатие при посадке)
            squishY = 1.0 + (if (p < 0.5) 0.15 else -0.09) * jumpArc
        } else if (squishTicks > 0) {
            squishY = 0.86 // Мягкое сплющивание при приземлении
        }
    }
    val squishXZ = if (squishY != 1.0) 1.0 / sqrt(squishY) else 1.0

    // Парение: мягкая синусоида только в режиме "Парение"
    val time = (tickCount.toDouble() + delta) * 0.05
    val hoverOffset = if (!isJumpMode) sin(time * 2.8) * (0.055 * petSize) else 0.0

    val center = Vec(ix, iy + currentJumpY + hoverOffset, iz)
    val relCenter = center.subtract(cam)

    // Интерполяция углов Yaw и Pitch (наклон по вектору движения)
    val yDiff = wrapDegrees(currentYaw - previousYaw)
    val yawDeg = previousYaw + yDiff * delta
    val yawRad = Math.toRadians(yawDeg)

    val pitchDeg = previousPitch + (currentPitch - previousPitch) * delta
    val pitchRad = Math.toRadians(pitchDeg)

    val cosY = cos(yawRad); val sinY = sin(yawRad)
    val cosP = cos(pitchRad); val sinP = sin(pitchRad)

    // Трансформация вершин с учетом наклона (Pitch), вращения (Yaw) и деформации
    fun transformLocal(lx: Double, ly: Double, lz: Double, scale: Double): Vec {
        // Наклон вперед по Pitch вокруг локальной оси X
        val py_pitched = ly * cosP - lz * sinP
        val pz_pitched = ly * sinP + lz * cosP
        val px_pitched = lx

        val sx = px_pitched * squishXZ
        val sy = py_pitched * squishY
        val sz = pz_pitched * squishXZ

        val rx = sx * cosY + sz * sinY
        val ry = sy
        val rz = -sx * sinY + sz * cosY
        return Vec(center.x() + rx * scale, center.y() + ry * scale, center.z() + rz * scale)
    }

    fun isFacingCamera(pos: Vec, norm: Vec): Boolean {
        val toCamX = cam.x() - pos.x()
        val toCamY = cam.y() - pos.y()
        val toCamZ = cam.z() - pos.z()
        return (norm.x() * toCamX + norm.y() * toCamY + norm.z() * toCamZ) > 0.0
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. ДИНАМИЧЕСКАЯ ТЕНЬ НА ЗЕМЛЕ
    // ─────────────────────────────────────────────────────────────────────────
    val groundBlockY = findGroundLevel(center.x(), center.y(), center.z())
    val groundY = groundBlockY + 0.015
    val distToGround = center.y() - R * squishY - groundBlockY

    if (distToGround in -0.5..4.5) {
        val factor = (1.0 - (distToGround.coerceAtLeast(0.0) / 4.5)).coerceIn(0.1, 1.0)
        val shadowR = R * 0.9 * squishXZ * (0.75 + 0.35 * factor)
        val shadowAlpha = (130 * factor).toInt().coerceIn(15, 130)
        val shadowColor = Colors.rgba(0, 0, 0, shadowAlpha)

        val shadowVerts = ArrayList<Vec>()
        val steps = 16
        for (k in 0 until steps) {
            val a = 2.0 * PI * k / steps
            shadowVerts.add(Vec(center.x() + cos(a) * shadowR, groundY, center.z() + sin(a) * shadowR))
        }
        render.polygon(shadowVerts, shadowColor, false)
        render.polygon(shadowVerts.reversed(), shadowColor, false)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. ИДЕАЛЬНО СГЛАЖЕННАЯ СФЕРА (GPU Mesh 32x32)
    // ─────────────────────────────────────────────────────────────────────────
    val verts = sphereMesh.verts()
    val idx = sphereMesh.idx()

    if (idx != null) {
        val stacks = 32
        val slices = 32

        for (i in 0..stacks) {
            val phi = PI * (-0.5 + i.toDouble() / stacks)
            val y = sin(phi)
            val r = cos(phi)

            for (j in 0..slices) {
                val theta = 2.0 * PI * j.toDouble() / slices
                val nx = r * sin(theta)
                val ny = y
                val nz = r * cos(theta)

                // Нормаль с наклоном Pitch и поворотом Yaw
                val nyp = ny * cosP - nz * sinP
                val nzp = ny * sinP + nz * cosP
                val nxp = nx

                val normX = (nxp * cosY + nzp * sinY).toFloat()
                val normY = nyp.toFloat()
                val normZ = (-nxp * sinY + nzp * cosY).toFloat()

                // Позиция вершины
                val p = transformLocal(nx, ny, nz, R)
                val px = (p.x() - cam.x()).toFloat()
                val py = (p.y() - cam.y()).toFloat()
                val pz = (p.z() - cam.z()).toFloat()

                verts.putVec3(px, py, pz).putVec3(normX, normY, normZ).next()
            }
        }

        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val first = i * (slices + 1) + j
                val second = first + slices + 1
                idx.putTriangle(first, second, first + 1)
                idx.putTriangle(second, second + 1, first + 1)
            }
        }

        sphereShader.setMat4("u_view", e.viewMatrix())
        sphereShader.setMat4("u_projection", e.projectionMatrix())
        sphereShader.set("u_lightDir", lightDir.x().toFloat(), lightDir.y().toFloat(), lightDir.z().toFloat())
        spherePass.draw()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. ОТСЕЧЕНИЕ ЛИЦА ПРИ ВЗГЛЯДЕ СЗАДИ
    // ─────────────────────────────────────────────────────────────────────────
    val faceFwd = Vec(sin(yawRad) * cosP, -sinP, cos(yawRad) * cosP)
    val toCam = Vec(cam.x() - center.x(), cam.y() - center.y(), cam.z() - center.z())
    val faceDotCam = faceFwd.x() * toCam.x() + faceFwd.y() * toCam.y() + faceFwd.z() * toCam.z()

    if (faceDotCam <= 0.02) return@on

    // ─────────────────────────────────────────────────────────────────────────
    // 5. ГЛАЗА VERITY
    // ─────────────────────────────────────────────────────────────────────────
    val faceScale = R * 1.012
    val eyeCenterPhi = Math.toRadians(21.0)
    val eyePhiRad = Math.toRadians(11.5)
    val eyeThetaRad = Math.toRadians(4.6)
    val eyeDistTheta = Math.toRadians(13.2)

    fun drawEye(centerTheta: Double) {
        val ey = sin(eyeCenterPhi)
        val er = cos(eyeCenterPhi)
        val eyeMid = transformLocal(er * sin(centerTheta), ey, er * cos(centerTheta), faceScale)
        val eyeNorm = Vec(eyeMid.x() - center.x(), eyeMid.y() - center.y(), eyeMid.z() - center.z())
        if (!isFacingCamera(eyeMid, eyeNorm)) return

        val eyeVerts = ArrayList<Vec>()
        val segments = 14
        for (k in 0 until segments) {
            val a = 2.0 * PI * k / segments
            val ep = eyeCenterPhi + sin(a) * eyePhiRad
            val et = centerTheta + cos(a) * eyeThetaRad
            val y = sin(ep)
            val r = cos(ep)
            eyeVerts.add(transformLocal(r * sin(et), y, r * cos(et), faceScale))
        }
        render.polygon(eyeVerts, Colors.BLACK, false)
    }

    drawEye(-eyeDistTheta)
    drawEye(eyeDistTheta)

    // ─────────────────────────────────────────────────────────────────────────
    // 6. УЛЫБКА VERITY
    // ─────────────────────────────────────────────────────────────────────────
    val smileSegments = 18
    val smileThickness = Math.toRadians(2.8)

    fun smilePoint(t: Double, phiOffset: Double): Vec {
        val theta = t * Math.toRadians(36.0)
        val phi = Math.toRadians(-24.0 + (t * t) * 19.0) + phiOffset
        val y = sin(phi)
        val r = cos(phi)
        return transformLocal(r * sin(theta), y, r * cos(theta), faceScale)
    }

    for (s in 0 until smileSegments) {
        val t0 = -1.0 + 2.0 * s / smileSegments
        val t1 = -1.0 + 2.0 * (s + 1) / smileSegments

        val top0 = smilePoint(t0, smileThickness)
        val bot0 = smilePoint(t0, -smileThickness)
        val top1 = smilePoint(t1, smileThickness)
        val bot1 = smilePoint(t1, -smileThickness)

        val quadMid = Vec(
            (top0.x() + top1.x() + bot1.x() + bot0.x()) * 0.25,
            (top0.y() + top1.y() + bot1.y() + bot0.y()) * 0.25,
            (top0.z() + top1.z() + bot1.z() + bot0.z()) * 0.25
        )
        val quadNorm = Vec(quadMid.x() - center.x(), quadMid.y() - center.y(), quadMid.z() - center.z())
        if (!isFacingCamera(quadMid, quadNorm)) continue

        render.quad(top0, top1, bot1, bot0, Colors.BLACK, false)
    }

    fun drawSmileTick(tSide: Double) {
        val cornerTheta = tSide * Math.toRadians(36.0)
        val basePhi = Math.toRadians(-5.0)
        val tickH = Math.toRadians(6.5)
        val tickW = Math.toRadians(2.2)

        val p1 = transformLocal(cos(basePhi - tickH) * sin(cornerTheta - tickW), sin(basePhi - tickH), cos(basePhi - tickH) * cos(cornerTheta - tickW), faceScale)
        val p2 = transformLocal(cos(basePhi - tickH) * sin(cornerTheta + tickW), sin(basePhi - tickH), cos(basePhi - tickH) * cos(cornerTheta + tickW), faceScale)
        val p3 = transformLocal(cos(basePhi + tickH) * sin(cornerTheta + tickW), sin(basePhi + tickH), cos(basePhi + tickH) * cos(cornerTheta + tickW), faceScale)
        val p4 = transformLocal(cos(basePhi + tickH) * sin(cornerTheta - tickW), sin(basePhi + tickH), cos(basePhi + tickH) * cos(cornerTheta - tickW), faceScale)

        val mid = Vec(
            (p1.x() + p2.x() + p3.x() + p4.x()) * 0.25,
            (p1.y() + p2.y() + p3.y() + p4.y()) * 0.25,
            (p1.z() + p2.z() + p3.z() + p4.z()) * 0.25
        )
        val norm = Vec(mid.x() - center.x(), mid.y() - center.y(), mid.z() - center.z())
        if (!isFacingCamera(mid, norm)) return

        render.quad(p1, p2, p3, p4, Colors.BLACK, false)
    }

    drawSmileTick(-1.0)
    drawSmileTick(1.0)
}
