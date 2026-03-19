package org.gpur.preload;

public final class GPurElytraPreloadMath {
    private GPurElytraPreloadMath() {
    }

    public static PreloadProfile inactive(final int baseLookahead, final double angleDegrees) {
        return new PreloadProfile(false, 0.0D, 0.0D, 0.0D, Math.max(1, baseLookahead), Math.cos(Math.toRadians(angleDegrees)));
    }

    public static PreloadProfile fromMotion(
        final boolean enabled,
        final boolean fallFlying,
        final double motionX,
        final double motionZ,
        final double lookX,
        final double lookZ,
        final int baseLookahead,
        final double lookaheadFactor,
        final double angleDegrees,
        final double lookDirectionBlend,
        final int turnLookaheadBoost,
        final double turnAngleBoostDegrees
    ) {
        final double horizontalSpeedPerTick = Math.sqrt((motionX * motionX) + (motionZ * motionZ));
        final double horizontalSpeedPerSecond = horizontalSpeedPerTick * 20.0D;

        if (!enabled || !fallFlying || horizontalSpeedPerSecond <= 0.01D) {
            return inactive(baseLookahead, angleDegrees);
        }

        final double motionDirectionX = motionX / horizontalSpeedPerTick;
        final double motionDirectionZ = motionZ / horizontalSpeedPerTick;
        final double lookLength = Math.sqrt((lookX * lookX) + (lookZ * lookZ));

        double predictedDirectionX = motionDirectionX;
        double predictedDirectionZ = motionDirectionZ;
        double turnSeverity = 0.0D;

        if (lookLength > 1.0E-4D) {
            final double lookDirectionX = lookX / lookLength;
            final double lookDirectionZ = lookZ / lookLength;
            final double alignment = clamp((motionDirectionX * lookDirectionX) + (motionDirectionZ * lookDirectionZ), -1.0D, 1.0D);
            turnSeverity = (1.0D - alignment) * 0.5D;

            final double adaptiveLookBlend = clamp(lookDirectionBlend + (turnSeverity * 0.45D), 0.0D, 0.9D);
            final double blendedDirectionX = (motionDirectionX * (1.0D - adaptiveLookBlend)) + (lookDirectionX * adaptiveLookBlend);
            final double blendedDirectionZ = (motionDirectionZ * (1.0D - adaptiveLookBlend)) + (lookDirectionZ * adaptiveLookBlend);
            final double blendedLength = Math.sqrt((blendedDirectionX * blendedDirectionX) + (blendedDirectionZ * blendedDirectionZ));

            if (blendedLength > 1.0E-4D) {
                predictedDirectionX = blendedDirectionX / blendedLength;
                predictedDirectionZ = blendedDirectionZ / blendedLength;
            }
        }

        final int lookaheadChunks = Math.max(
            baseLookahead,
            (int)Math.ceil(horizontalSpeedPerSecond * lookaheadFactor) + (int)Math.ceil(turnSeverity * turnLookaheadBoost)
        );
        final double adjustedAngleDegrees = clamp(angleDegrees + (turnSeverity * turnAngleBoostDegrees), 1.0D, 89.0D);

        return new PreloadProfile(
            true,
            predictedDirectionX,
            predictedDirectionZ,
            horizontalSpeedPerSecond,
            lookaheadChunks,
            Math.cos(Math.toRadians(adjustedAngleDegrees))
        );
    }

    public static boolean isWithinPreloadCone(
        final PreloadProfile profile,
        final int centerChunkX,
        final int centerChunkZ,
        final int chunkX,
        final int chunkZ
    ) {
        if (!profile.active()) {
            return false;
        }

        final int dx = chunkX - centerChunkX;
        final int dz = chunkZ - centerChunkZ;
        final int squareDistance = Math.max(Math.abs(dx), Math.abs(dz));
        if (squareDistance > profile.lookaheadChunks()) {
            return false;
        }

        final double projection = (dx * profile.directionX()) + (dz * profile.directionZ());
        if (projection <= 0.0D) {
            return false;
        }

        final double distance = Math.sqrt((dx * dx) + (double)(dz * dz));
        return distance > 0.0D && (projection / distance) >= profile.coneCosThreshold();
    }

    public static double forwardPriorityScore(
        final PreloadProfile profile,
        final int centerChunkX,
        final int centerChunkZ,
        final int chunkX,
        final int chunkZ
    ) {
        final int dx = chunkX - centerChunkX;
        final int dz = chunkZ - centerChunkZ;
        final double projection = (dx * profile.directionX()) + (dz * profile.directionZ());
        final double lateralPenalty = Math.abs((dx * profile.directionZ()) - (dz * profile.directionX()));
        return (projection * 16.0D) - lateralPenalty;
    }

    public record PreloadProfile(
        boolean active,
        double directionX,
        double directionZ,
        double horizontalSpeedBlocksPerSecond,
        int lookaheadChunks,
        double coneCosThreshold
    ) {
    }

    private static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }
}
