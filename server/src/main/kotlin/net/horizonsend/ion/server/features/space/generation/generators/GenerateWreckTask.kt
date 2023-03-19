package net.horizonsend.ion.server.features.space.generation.generators

import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.session.ClipboardHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import net.horizonsend.ion.server.features.space.encounters.SecondaryChests
import net.horizonsend.ion.server.features.space.generation.BlockSerialization
import net.horizonsend.ion.server.features.space.generation.SpaceGenerationManager
import net.horizonsend.ion.server.miscellaneous.NamespacedKeys
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtUtils
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.starlegacy.util.nms
import net.starlegacy.util.toBukkitBlockData
import net.starlegacy.util.worldEditSession
import org.bukkit.Chunk
import org.bukkit.persistence.PersistentDataType
import java.io.ByteArrayOutputStream

class GenerateWreckTask(
	override val generator: SpaceGenerator,
	private val wreck: SpaceGenerator.WreckGenerationData
) : SpaceGenerationTask<WreckGenerationData.WreckReturnData>() {
	val serverLevel = generator.serverLevel

	override val returnData = CompletableDeferred<WreckGenerationData.WreckReturnData>()

	val clipboard: Clipboard = generator.schematicMap[wreck.schematicName]!!

	val region = clipboard.region.clone()
	private val targetBlockVector: BlockVector3 = BlockVector3.at(wreck.x, wreck.y, wreck.z)
	private val offset: BlockVector3 = targetBlockVector.subtract(clipboard.origin)

	private val encounter = wreck.encounter?.getEncounter()
	var encounterPrimaryChest: Pair<ChunkPos, BlockPos>? = null
	val secondaryChests: MutableMap<ChunkPos, MutableList<CompoundTag>> = mutableMapOf()

	override fun generate() {
		val sectionMap = mutableMapOf<ChunkPos, List<SpaceGenerationReturnData.CompletedSection>>()
		SpaceGenerationManager.coroutineScope.launch {
			serverLevel.world.worldEditSession(true) {
				region.shift(offset)

				val holder = ClipboardHolder(clipboard)
				holder.transform = com.sk89q.worldedit.math.transform.AffineTransform().rotateY(generator.random.nextDouble(0.0, 180.0))
				holder.clipboard

				val sectionsSet = mutableSetOf<Int>()

				for (
				y in (region.boundingBox.minimumY - serverLevel.minBuildHeight)..(region.boundingBox.maximumY - serverLevel.minBuildHeight)
				) {
					sectionsSet.add(y.shr(4))
				}

				for (chunkPosWE in region.chunks) {
					val nmsChunkPos = ChunkPos(chunkPosWE.x, chunkPosWE.z)
					val chunkOriginX = chunkPosWE.x * 16
					val chunkOriginZ = chunkPosWE.z * 16

					val chunkCompletedSections = mutableListOf<SpaceGenerationReturnData.CompletedSection>()

					for (sectionPos in sectionsSet) {
						val newlyCompleted = generateSection(
							sectionPos,
							chunkOriginX,
							chunkOriginZ,
							nmsChunkPos
						) ?: continue

						chunkCompletedSections.add(newlyCompleted)
					}

					if (chunkCompletedSections.isEmpty()) continue

					sectionMap[nmsChunkPos] = chunkCompletedSections
				}

				val serializedWreckData: Pair<ChunkPos, CompoundTag>? = encounterPrimaryChest?.let { chestPos ->
					// Won't be null if the encounter chest is not null, unless someone messed up reaaalllly bad
					val chestPosCompound = wreck.encounter!!.nms(
						chestPos.second.x,
						chestPos.second.y,
						chestPos.second.z
					)

					return@let chestPos.first to chestPosCompound
				}

				returnData.complete(
					WreckGenerationData.WreckReturnData(
						sectionMap,
						serializedWreckData,
						secondaryChests
					)
				)
			}
		}
	}

	private fun generateSection(
		sectionY: Int,
		chunkMinX: Int,
		chunkMinZ: Int,
		chunkPos: ChunkPos
	): SpaceGenerationReturnData.CompletedSection? {
		val palette = mutableSetOf<Pair<BlockState, CompoundTag?>>()
		val storedBlocks = arrayOfNulls<Int>(4096)
		var index = 0
		val sectionMinY = sectionY.shl(4)

		palette.add(Blocks.AIR.defaultBlockState() to null)
		val paletteListTag = ListTag()

		for (x in 0..15) {
			val worldX = x + chunkMinX

			for (z in 0..15) {
				val worldZ = z + chunkMinZ

				for (y in 0..15) {
					val worldY = sectionMinY + y
					val schematicRelative = BlockVector3.at(worldX, worldY, worldZ).subtract(offset)

					val baseBlock = clipboard.getFullBlock(schematicRelative)
					val originalBlockState: BlockState = baseBlock.toImmutableState().toBukkitBlockData().nms
					val blockNBT = if (originalBlockState.hasBlockEntity()) baseBlock.nbtData else null

					if (originalBlockState.isAir) {
						storedBlocks[index] = 0
						index++
						continue
					}

					var combined = originalBlockState to blockNBT?.nms()

					blockNBT?.let blockNBT@{
						val name = blockNBT.getString("CustomName")

						encounter?.let encounter@{ encounter ->
							if (originalBlockState.block != Blocks.CHEST) return@encounter
							if (!name.contains("Encounter Chest", true)) return@encounter

							encounterPrimaryChest = chunkPos to BlockPos(worldX, worldY, worldZ)
							combined = encounter.constructChestState()
						}

						// Use let to be able to exit out of the statement
						if (name.contains("Secondary: ", ignoreCase = true)) {
							let secondaryChest@{
								val chestType = name.substringAfter("Secondary: ").substringBefore("\"")

								val secondaryChest = SecondaryChests[chestType] ?: return@secondaryChest

								combined = secondaryChest.blockState to secondaryChest.NBT

								val serialized = CompoundTag()

								serialized.putInt("x", worldX)
								serialized.putInt("y", worldY)
								serialized.putInt("z", worldZ)

								secondaryChest.money?.let { money -> serialized.putInt("Money", money) }

								val newList = secondaryChests.getOrDefault(chunkPos, mutableListOf())
								newList.add(serialized)

								secondaryChests[chunkPos] = newList
							}
						}
					}

					// Format the block entity
					(combined.second)?.putInt("x", worldX)
					(combined.second)?.putInt("y", worldY)
					(combined.second)?.putInt("z", worldZ)

					palette.add(combined)
					storedBlocks[index] = palette.indexOf(combined)

					index++
				}
			}
		}

		if (storedBlocks.all { it == 0 }) return null // don't write it if it's all empty

		palette.forEach { blockState ->
			val base = NbtUtils.writeBlockState(blockState.first)
			blockState.second?.let { base.put("TileEntity", it) }
			paletteListTag.add(base)
		}

		val intArray = storedBlocks.requireNoNulls().toIntArray()

		return SpaceGenerationReturnData.CompletedSection(
			sectionY,
			intArray,
			palette,
			paletteListTag
		)
	}
}

/**
 * This class contains information passed to the generation function.
 * @param [x, y ,z] Origin of the asteroid.
 * @param wreckName Name of the wreck schematic
 * @param encounterIdentifier Wreck encounter identifier
 **/
data class WreckGenerationData(
	override val x: Int,
	override val y: Int,
	override val z: Int,
	val wreckName: String,
	val encounterIdentifier: String? = null
) : SpaceGenerationData() {

	data class WreckReturnData(
		override val completedSectionMap: Map<ChunkPos, List<CompletedSection>>,
		val serializedWreckData: Pair<ChunkPos, CompoundTag>? = null,
		val secondaryChests: Map<ChunkPos, List<CompoundTag>>
	) : SpaceGenerationReturnData() {
		@OptIn(ExperimentalCoroutinesApi::class)
		override fun complete(generator: SpaceGenerator): Deferred<Map<ChunkPos, Chunk>> {
			val chunks = super.complete(generator)

			chunks.invokeOnCompletion {
				val finishedChunks = chunks.getCompleted()

				serializedWreckData?.let { (chunkPos, data) ->

					// It is most definitely inside the covered chunks
					val encounterChunk = finishedChunks[chunkPos]!!

					val existingWrecksBaseTag = BlockSerialization
						.readChunkCompoundTag(
							encounterChunk,
							NamespacedKeys.WRECK_ENCOUNTER_DATA
						)

					// list of compound tags (10)
					val existingWrecks = existingWrecksBaseTag?.getList("Wrecks", 10) ?: ListTag()

					existingWrecks.add(data)

					val newFinishedData = CompoundTag()

					newFinishedData.put("Wrecks", existingWrecks)

					val wreckDataOutputStream = ByteArrayOutputStream()
					NbtIo.writeCompressed(newFinishedData, wreckDataOutputStream)

					encounterChunk.persistentDataContainer.set(
						NamespacedKeys.WRECK_ENCOUNTER_DATA,
						PersistentDataType.BYTE_ARRAY,
						wreckDataOutputStream.toByteArray()
					)
				}

				for ((chunkPos, chests) in secondaryChests) {
					val encounterChunk = finishedChunks[chunkPos]!!

					val existingWrecksBaseTag = BlockSerialization
						.readChunkCompoundTag(
							encounterChunk,
							NamespacedKeys.WRECK_ENCOUNTER_DATA
						)

					// list of compound tags (10)
					val existingChests = existingWrecksBaseTag?.getList("SecondaryChests", 10) ?: ListTag()
					val existingWrecks = existingWrecksBaseTag?.getList("Wrecks", 10) ?: ListTag()

					for (chest in chests) {
						existingChests.add(chest)
					}

					val newFinishedData = CompoundTag()

					newFinishedData.put("SecondaryChests", existingChests)
					newFinishedData.put("Wrecks", existingWrecks)

					val wreckDataOutputStream = ByteArrayOutputStream()
					NbtIo.writeCompressed(newFinishedData, wreckDataOutputStream)

					encounterChunk.persistentDataContainer.set(
						NamespacedKeys.WRECK_ENCOUNTER_DATA,
						PersistentDataType.BYTE_ARRAY,
						wreckDataOutputStream.toByteArray()
					)
				}
			}

			return chunks
		}
	}
}