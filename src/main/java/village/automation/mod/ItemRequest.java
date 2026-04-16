package village.automation.mod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import village.automation.mod.entity.JobType;

import java.util.UUID;

public class ItemRequest {

    private final UUID     workerUUID;
    private final String   workerName;
    private final JobType  jobType;
    private final ItemStack requestedItem;
    private final long     timestamp;

    /** Creates a new request with the current time as its timestamp. */
    public ItemRequest(UUID workerUUID, String workerName, JobType jobType, ItemStack requestedItem) {
        this(workerUUID, workerName, jobType, requestedItem, System.currentTimeMillis());
    }

    /** Full constructor used by deserialization (NBT / packet). */
    public ItemRequest(UUID workerUUID, String workerName, JobType jobType,
                       ItemStack requestedItem, long timestamp) {
        this.workerUUID    = workerUUID;
        this.workerName    = workerName;
        this.jobType       = jobType;
        this.requestedItem = requestedItem.copy();
        this.timestamp     = timestamp;
    }

    public UUID      getWorkerUUID()    { return workerUUID;    }
    public String    getWorkerName()    { return workerName;    }
    public JobType   getJobType()       { return jobType;       }
    public ItemStack getRequestedItem() { return requestedItem; }
    public long      getTimestamp()     { return timestamp;     }

    // ── NBT ───────────────────────────────────────────────────────────────────

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("WorkerUUID",  workerUUID);
        tag.putString("WorkerName", workerName);
        tag.putString("JobType",    jobType.name());
        tag.putString("Item",       BuiltInRegistries.ITEM.getKey(requestedItem.getItem()).toString());
        tag.putLong("Timestamp",    timestamp);
        return tag;
    }

    public static ItemRequest load(CompoundTag tag) {
        UUID    uuid     = tag.getUUID("WorkerUUID");
        String  name     = tag.getString("WorkerName");
        JobType job      = JobType.valueOf(tag.getString("JobType"));
        long    ts       = tag.getLong("Timestamp");

        ResourceLocation rl   = ResourceLocation.parse(tag.getString("Item"));
        Item             item = BuiltInRegistries.ITEM.getOptional(rl).orElse(Items.AIR);

        return new ItemRequest(uuid, name, job, new ItemStack(item), ts);
    }
}
