package com.rlclones.gametest;
import com.rlclones.ai.*;
import com.rlclones.clone.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import net.minecraftforge.gametest.*;
@GameTestHolder("rlclones_community") @PrefixGameTestTemplate(false)
public final class CommunityResourcesTests {
    private static List<Recipe<?>> recipes;
    private static List<Bases.Plan> oldPlans;private static List<Bases.Base> oldBases;private static Set<String> oldProjects;
    @AfterBatch(batch="coop-iron")public static void ironCleanup(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="coop-copper")public static void copperCleanup(ServerLevel l){if(recipes!=null){l.getRecipeManager().replaceRecipes(recipes);recipes=null;Crafting.invalidateRecipes();}cleanup(l);}
    @AfterBatch(batch="coop-safety")public static void safetyCleanup(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="coop-full")public static void fullCleanup(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="classification")public static void classificationCleanup(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="masonry-static")public static void masonryCleanup(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="masonry-real")public static void realCleanup(ServerLevel l){cleanup(l);var b=Bases.get(l.getServer());if(oldPlans!=null){b.plans.clear();b.plans.addAll(oldPlans);b.bases.clear();b.bases.addAll(oldBases);b.projects.clear();b.projects.addAll(oldProjects);oldPlans=null;}}
    private static void cleanup(ServerLevel l){WorkReservations.clear();ArmorCoop.clear();GearRecipeTests.cleanup(l);}
    private static void arena(GameTestHelper h){for(var p:BlockPos.betweenClosed(new BlockPos(0,1,0),new BlockPos(18,11,18))){boolean shell=p.getY()==1||p.getY()==11||p.getX()==0||p.getX()==18||p.getZ()==0||p.getZ()==18;h.setBlock(p,shell?Blocks.STONE:Blocks.AIR);}}
    private static ClonePlayer actor(GameTestHelper h,int x,int z){var c=GearRecipeTests.clone(h);var p=h.absoluteVec(new Vec3(x+.5,2,z+.5));c.teleportTo(p.x,p.y,p.z);c.setYRot(-90);c.setYHeadRot(-90);c.setXRot(0);c.setOnGround(true);c.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NIGHT_VISION,1000));return c;}
    private static List<ClonePlayer> group(GameTestHelper h,Item material,int...counts){var all=new ArrayList<ClonePlayer>();for(int i=0;i<counts.length;i++){var c=actor(h,5+i,7);c.getInventory().setItem(0,new ItemStack(material,counts[i]));c.getInventory().setItem(4,new ItemStack(Items.CRAFTING_TABLE));c.setAiEnabled(true);all.add(c);}return all;}
    private static boolean plan(List<ClonePlayer> all,long now){boolean planned=false;for(var c:all)planned|=c.controller().armorCoop().plan(now);for(var c:all)c.setAiEnabled(false);return planned;}
    private static int total(List<ClonePlayer> all,Item item){return all.stream().mapToInt(c->c.getInventory().countItem(item)).sum();}
    private static int armor(List<ClonePlayer> all){int n=0;for(var c:all){for(var s:c.getInventory().items)if(s.getItem() instanceof ArmorItem)n+=s.getCount();for(var s:c.getInventory().armor)if(s.getItem() instanceof ArmorItem)n+=s.getCount();}return n;}
    private static void work(GameTestHelper h,List<ClonePlayer> all){h.onEachTick(()->{for(var c:all){c.controller().armorCoop().tick(h.getLevel().getGameTime());if(c.controller().crafting().forcedTarget instanceof ArmorItem)c.controller().crafting().tick();c.controller().motor().tick();Equipment.manage(c,true);}});}
    @GameTest(template="arena",batch="coop-iron",timeoutTicks=320)
    public static void scatteredIronMakesActualArmorWithoutDuplicatingIngots(GameTestHelper h){arena(h);var all=group(h,Items.IRON_INGOT,1,1,2);h.assertTrue(plan(all,h.getLevel().getGameTime()),"collective recipe is planned even though nobody has four ingots");work(h,all);
        h.succeedWhen(()->{h.assertTrue(armor(all)==1,"one real armor item crafted: "+all.stream().map(c->c.controller().crafting().debug()+" "+c.controller().armorCoop().report()).toList());h.assertTrue(total(all,Items.IRON_INGOT)==0,"four real ingots consumed exactly once");});}
    @GameTest(template="arena",batch="coop-copper",timeoutTicks=320)
    public static void modRecipeWithVanillaArmorIdAlsoPoolsCopper(GameTestHelper h){arena(h);recipes=new ArrayList<>(h.getLevel().getRecipeManager().getRecipes());var mixed=new ArrayList<>(recipes);var ingredients=NonNullList.withSize(6,Ingredient.EMPTY);for(int i:new int[]{0,2,3,5})ingredients.set(i,Ingredient.of(Items.COPPER_INGOT));mixed.add(new ShapedRecipe(new ResourceLocation("coop_test","copper_boots"),"",CraftingBookCategory.EQUIPMENT,3,2,ingredients,new ItemStack(Items.CHAINMAIL_BOOTS)));h.getLevel().getRecipeManager().replaceRecipes(mixed);Crafting.invalidateRecipes();var all=group(h,Items.COPPER_INGOT,1,1,1,1);
        h.assertTrue(plan(all,h.getLevel().getGameTime()),"actual mod/datapack armor recipe is used");work(h,all);h.succeedWhen(()->{h.assertTrue(armor(all)==1,"real recipe creates armor");h.assertTrue(total(all,Items.COPPER_INGOT)==0,"copper is consumed through crafting");});}
    @GameTest(template="arena",batch="coop-safety",timeoutTicks=40)
    public static void otherTeamsAndInterruptedDeliveriesDoNotLoseResources(GameTestHelper h){arena(h);var all=group(h,Items.IRON_INGOT,2,2);all.get(1).setCloneTeam("other");h.assertTrue(!plan(all,h.getLevel().getGameTime()),"other team resources cannot be pooled");for(var c:all){c.setCloneTeam(ClonePlayer.DEFAULT_TEAM);c.setAiEnabled(true);}h.assertTrue(plan(all,h.getLevel().getGameTime()),"same team can plan");all.get(0).setHealth(3);for(var c:all)c.controller().armorCoop().tick(h.getLevel().getGameTime());h.assertTrue(total(all,Items.IRON_INGOT)==4&&armor(all)==0,"cancelled work leaves every real material intact");h.succeed();}
    @GameTest(template="arena",batch="coop-full",timeoutTicks=40)
    public static void fullReceiverRollsBackUnacceptedItems(GameTestHelper h){arena(h);var receiver=actor(h,5,7);for(int i=0;i<receiver.getInventory().items.size();i++)receiver.getInventory().setItem(i,new ItemStack(Items.STONE,64));receiver.getInventory().setItem(4,new ItemStack(Items.CRAFTING_TABLE));receiver.setAiEnabled(true);var all=new ArrayList<ClonePlayer>();all.add(receiver);for(int i=0;i<4;i++){var c=actor(h,6+i,7);c.getInventory().setItem(0,new ItemStack(Items.IRON_INGOT));c.setAiEnabled(true);all.add(c);}h.assertTrue(plan(all,h.getLevel().getGameTime()),"full recipient is a valid material collection attempt");for(var c:all)c.controller().armorCoop().tick(h.getLevel().getGameTime());h.assertTrue(total(all,Items.IRON_INGOT)==4&&receiver.getInventory().countItem(Items.IRON_INGOT)==0,"unaccepted pieces are returned, never erased or duplicated");h.succeed();}
    @GameTest(template="arena",batch="classification",timeoutTicks=40)
    public static void cachedClassificationsMatchAndReuseUnchangedStates(GameTestHelper h){var states=List.of(Blocks.DIRT.defaultBlockState(),Blocks.GRASS_BLOCK.defaultBlockState(),Blocks.ANDESITE.defaultBlockState(),Blocks.IRON_ORE.defaultBlockState(),Blocks.OAK_LOG.defaultBlockState(),Blocks.CRAFTING_TABLE.defaultBlockState(),Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));Perception.invalidateClassifications();long misses=Perception.classificationMisses();for(var s:states)h.assertTrue(Perception.classify(s)==Perception.classifyUncached(s),"same classification");for(int i=0;i<20000;i++)Perception.classify(states.get(i%states.size()));h.assertTrue(Perception.classificationMisses()-misses==states.size(),"unchanged terrain avoids repeated classification");long before=Perception.classificationMisses();Perception.invalidateClassifications();Perception.classify(states.get(0));h.assertTrue(Perception.classificationMisses()==before+1,"tag/resource invalidation forces fresh evaluation");h.succeed();}
    @GameTest(template="arena",batch="classification",timeoutTicks=40)
    public static void worldChangesAndPersonalHazardKnowledgeRemainLive(GameTestHelper h){arena(h);var c=actor(h,5,7);var p=h.absolutePos(new BlockPos(9,2,7));h.getLevel().setBlock(p,Blocks.DIRT.defaultBlockState(),3);c.controller().perception().setUnknown((s,pos)->false);c.controller().perception().setHarmful(id->false);h.assertTrue(c.controller().perception().kindAt(p)==null,"ordinary dirt is not a resource");c.controller().perception().setHarmful(id->id.equals("minecraft:dirt"));h.assertTrue(c.controller().perception().kindAt(p)==Perception.BlockKind.HARMFUL,"new hazard knowledge bypasses base cache");h.getLevel().setBlock(p,Blocks.IRON_ORE.defaultBlockState(),3);h.assertTrue(c.controller().perception().kindAt(p)==Perception.BlockKind.ORE,"changed ore is immediately recognized");h.succeed();}
    @GameTest(template="arena",batch="masonry-static",timeoutTicks=40)
    public static void buildingStonesAreNotDiscardedAndThreeBlueprintsAreDistinct(GameTestHelper h){arena(h);var c=actor(h,5,7);for(int i=0;i<36;i++)c.getInventory().setItem(i,new ItemStack(Items.GRAVEL,64));c.getInventory().setItem(0,new ItemStack(Items.ANDESITE,64));c.getInventory().setItem(1,new ItemStack(Items.GRANITE,64));c.getInventory().setItem(2,new ItemStack(Items.TUFF,64));c.controller().stairs().dropJunk();Storage.discardWorthless(c);h.assertTrue(c.getInventory().countItem(Items.ANDESITE)==64&&c.getInventory().countItem(Items.GRANITE)==64&&c.getInventory().countItem(Items.TUFF)==64,"architectural stone retained rather than deleted");h.assertTrue(Masonry.blueprint("STONE_PATH",BlockPos.ZERO).size()==14&&Masonry.blueprint("STONE_PLAZA",BlockPos.ZERO).size()==25&&Masonry.blueprint("STONE_SHELTER",BlockPos.ZERO).size()==17,"path, plaza and covered shelter are real distinct plans");h.succeed();}
    @GameTest(template="arena",batch="masonry-real",timeoutTicks=650)
    public static void surplusAndesiteBuildsARealSharedWalkway(GameTestHelper h){arena(h);var c=actor(h,5,5);c.getInventory().setItem(0,new ItemStack(Items.ANDESITE,64));var b=Bases.get(h.getLevel().getServer());oldPlans=new ArrayList<>(b.plans);oldBases=new ArrayList<>(b.bases);oldProjects=new HashSet<>(b.projects);b.plans.clear();b.bases.clear();b.projects.clear();b.add(h.getLevel().dimension(),c.blockPosition(),"masonry test");var plan=Masonry.propose(c);h.assertTrue(plan!=null&&plan.kind.equals("STONE_PATH"),"valid real shared plan created");var work=new PlanWork(c,c.controller().motor());work.begin(plan);h.onEachTick(()->{work.tick();c.controller().motor().tick();});h.succeedWhen(()->{h.assertTrue(plan.blocks.stream().allMatch(p->h.getLevel().getBlockState(p).is(Blocks.ANDESITE)),"all fourteen real andesite blocks placed: "+work.debug);h.assertTrue(c.getInventory().countItem(Items.ANDESITE)==50&&work.placed==14,"building consumes exactly fourteen actual blocks");});}
    @GameTest(template="arena",batch="range-eight",timeoutTicks=40)
    public static void eightChunkVisionRetainsTurningOcclusionAndUniqueCandidates(GameTestHelper h) {
        arena(h);
        var c=actor(h,7,7);
        var perception=c.controller().perception();
        h.assertTrue(perception.maxRange()==128.0,"default eight chunk radius");
        var base=c.blockPosition();
        var saved=new HashMap<BlockPos,BlockState>();
        var entities=new ArrayList<net.minecraft.world.entity.Entity>();
        try {
            for(int y=2;y<=134;y++) {
                var pos=base.above(y);saved.put(pos,h.getLevel().getBlockState(pos));
                h.getLevel().setBlock(pos,Blocks.AIR.defaultBlockState(),2);
            }
            var far=new net.minecraft.world.entity.decoration.ArmorStand(h.getLevel(),c.getX(),c.getY()+120,c.getZ());
            far.setNoGravity(true);h.getLevel().addFreshEntity(far);entities.add(far);
            var beyond=new net.minecraft.world.entity.decoration.ArmorStand(h.getLevel(),c.getX(),c.getY()+130,c.getZ());
            beyond.setNoGravity(true);h.getLevel().addFreshEntity(beyond);entities.add(beyond);
            // More than the 64 LOS budget behind the observer must not hide the far target.
            for(int i=0;i<70;i++) {
                var item=new net.minecraft.world.entity.item.ItemEntity(h.getLevel(),c.getX(),c.getY()+.1,c.getZ(),new ItemStack(Items.STICK));
                h.getLevel().addFreshEntity(item);entities.add(item);
            }
            c.setXRot(-90);long now=h.getLevel().getGameTime();
            perception.update(now);
            h.assertTrue(perception.canSee(far),"120 blocks visible");
            h.assertTrue(!perception.canSee(beyond),"130 blocks out of range");
            h.assertTrue(perception.visible().stream().anyMatch(v->v.entity==far),"off-screen crowd cannot consume LOS slots");
            c.setXRot(90);perception.update(now+1);
            h.assertTrue(perception.visible().stream().noneMatch(v->v.entity==far),"looking away hides distant target");
            c.setXRot(-90);perception.update(now+2);perception.update(now+3);
            h.assertTrue(perception.visible().stream().anyMatch(v->v.entity==far),"turning restores far candidate on cached scan");
            far.setPos(c.getX(),c.getY()+10,c.getZ());perception.update(now+4);perception.update(now+5);
            h.assertTrue(perception.visible().stream().filter(v->v.entity==far).count()==1,"near/far candidate remains unique");
            var wall=base.above(5);h.getLevel().setBlock(wall,Blocks.STONE.defaultBlockState(),2);
            c.setPos(c.getX()+0.01,c.getY(),c.getZ());
            h.assertTrue(!perception.canSee(far),"new wall blocks visibility after observer movement");
        } finally {
            for(var e:entities)e.discard();
            saved.forEach((pos,state)->h.getLevel().setBlock(pos,state,2));
            cleanup(h.getLevel());
        }
        h.succeed();
    }

    @AfterBatch(batch="population-cache") public static void cacheCleanup(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="population-reservations") public static void leaseCleanup(ServerLevel l){cleanup(l);}
    @AfterBatch(batch="population-replay") public static void replayCleanup(ServerLevel l){cleanup(l);}
    @GameTest(template="arena",batch="population-cache",timeoutTicks=40)
    public static void knownResourceIndexesAndStockCacheRefreshWithoutSharingPersonalKnowledge(GameTestHelper h) {
        arena(h);var a=actor(h,5,5);var b=actor(h,6,5);var vision=a.controller().perception();
        var local=new BlockPos(7,3,7);var world=h.absolutePos(local);h.setBlock(local,Blocks.IRON_ORE);
        vision.noteBlock(world);var list=vision.knownBlocks(Perception.BlockKind.ORE);
        h.assertTrue(list.contains(world)&&list==vision.knownBlocks(Perception.BlockKind.ORE),"unchanged known search results reused");
        h.assertTrue(!b.controller().perception().knownBlocks(Perception.BlockKind.ORE).contains(world),"no private discovery leak");
        h.setBlock(local,Blocks.CRAFTING_TABLE);vision.noteBlock(world);
        h.assertTrue(!vision.knownBlocks(Perception.BlockKind.ORE).contains(world)&&vision.knownBlocks(Perception.BlockKind.TABLE).contains(world),"changed discovery reindexed");
        vision.forgetBlock(world);h.assertTrue(vision.knownBlocks(Perception.BlockKind.TABLE).isEmpty(),"forgotten station removed");
        for(int i=0;i<140;i++){var p=new BlockPos(2+i%14,3+i/14,3);h.setBlock(p,Blocks.IRON_ORE);vision.noteBlock(h.absolutePos(p));}
        h.assertTrue(vision.knownBlocks(Perception.BlockKind.ORE).size()==128&&!vision.knownBlocks(Perception.BlockKind.ORE).contains(h.absolutePos(new BlockPos(2,3,3))),"eviction reflected in bounded resource index");
        var bases=Bases.get(h.getLevel().getServer());var chest=h.absolutePos(new BlockPos(15,2,15));var container=new net.minecraft.world.SimpleContainer(9);
        container.setItem(0,new ItemStack(Items.IRON_INGOT,2));bases.record(h.getLevel().dimension(),chest,container);
        var stock=bases.itemContents(h.getLevel().dimension(),chest);
        h.assertTrue(stock.get(Items.IRON_INGOT)==2&&stock==bases.itemContents(h.getLevel().dimension(),chest),"remembered stock decoded once");
        container.setItem(0,new ItemStack(Items.IRON_INGOT,5));bases.record(h.getLevel().dimension(),chest,container);
        h.assertTrue(bases.itemContents(h.getLevel().dimension(),chest).get(Items.IRON_INGOT)==5&&stock.get(Items.IRON_INGOT)==2,"new stock refreshes without mutating cached snapshot");h.succeed();
    }
    @GameTest(template="arena",batch="population-reservations",timeoutTicks=40)
    public static void chestLeasesReleaseExpireAndRespectDoubleChestAndWorlds(GameTestHelper h) {
        arena(h);var level=h.getLevel();var a=UUID.randomUUID();var b=UUID.randomUUID();long now=level.getGameTime();
        var p=h.absolutePos(new BlockPos(7,2,7));var q=p.east();
        var state=Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH);
        level.setBlock(p,state.setValue(ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.LEFT),2);
        level.setBlock(q,state.setValue(ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.RIGHT),2);
        h.assertTrue(WorkReservations.claim(level,p,a,now),"first worker reserves chest");
        h.assertTrue(!WorkReservations.claim(level,q,b,now),"other half of same chest unavailable");
        WorkReservations.release(level,b);h.assertTrue(!WorkReservations.available(level,p,b,now),"nonowner cannot release");
        h.assertTrue(WorkReservations.claim(level,p,a,now+50),"owner renews while walking");
        h.assertTrue(!WorkReservations.available(level,p,b,now+70)&&WorkReservations.available(level,p,b,now+111),"renewed lease expires after abandoned work");
        var nether=level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        h.assertTrue(WorkReservations.claim(nether,p,b,now),"same coordinate in other world independent");
        WorkReservations.release(level,a);h.assertTrue(WorkReservations.claim(level,q,b,now+51),"reset frees chest");
        WorkReservations.clear();h.succeed();
    }
    @GameTest(template="arena",batch="population-replay",timeoutTicks=160)
    public static void replayBudgetConservesWorkAndDirectDangerLearningIsImmediate(GameTestHelper h) {
        arena(h);var all=List.of(actor(h,5,5),actor(h,6,5));var manager=CloneManager.get(h.getLevel().getServer());
        for(var c:all)for(int i=0;i<100;i++)c.getCloneBrain().learn("test:danger",0,0,-5,0,true,0,0,1,false);
        long initial=all.stream().mapToLong(c->c.getCloneBrain().pendingReplay()).sum();
        h.assertTrue(initial==800&&all.stream().allMatch(c->c.getCloneBrain().ownUpdates==100),"own danger update immediate; all four rehearsals retained");
        h.onEachTick(()->{long before=all.stream().mapToLong(c->c.getCloneBrain().replayUpdates).sum();manager.tick();long after=all.stream().mapToLong(c->c.getCloneBrain().replayUpdates).sum();h.assertTrue(after-before<=256,"global rehearsal budget bounded");});
        h.succeedWhen(()->{long debt=all.stream().mapToLong(c->c.getCloneBrain().pendingReplay()).sum();long done=all.stream().mapToLong(c->c.getCloneBrain().replayUpdates).sum();h.assertTrue(debt+done==initial,"no dropped replay work");h.assertTrue(debt==0,"both brains eventually drain fairly");h.assertTrue(all.stream().allMatch(c->c.getCloneBrain().ownUpdates==100),"rehearsal does not duplicate real experiences");});
    }

    private static Bases.Base transferBase;
    @AfterBatch(batch="population-transfer") public static void transferCleanup(ServerLevel l){cleanup(l);Bases.get(l.getServer()).bases.remove(transferBase);transferBase=null;}
    @GameTest(template="arena",batch="population-transfer",timeoutTicks=100)
    public static void competingFetchesUseRealMenusAndConserveChestStock(GameTestHelper h) {
        arena(h);var a=actor(h,6,7);var b=actor(h,6,8);var all=List.of(a,b);
        var local=new BlockPos(8,2,7);h.setBlock(local,Blocks.CHEST);var pos=h.absolutePos(local);
        var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)h.getLevel().getBlockEntity(pos);
        chest.setItem(0,new ItemStack(Items.IRON_INGOT,4));
        var bases=Bases.get(h.getLevel().getServer());var base=bases.add(h.getLevel().dimension(),pos,"reservation transfer test");
        transferBase=base;bases.addChest(base,pos);bases.record(h.getLevel().dimension(),pos,chest);
        for(var c:all)c.controller().storage().beginSupply(stack->stack.is(Items.IRON_INGOT),2);
        h.onEachTick(()->{for(var c:all){var store=c.controller().storage();var status=store.tick();if(status!=Storage.Status.WORKING&&c.getInventory().countItem(Items.IRON_INGOT)<2)store.beginSupply(stack->stack.is(Items.IRON_INGOT),2);}});
        h.succeedWhen(()->{h.assertTrue(chest.countItem(Items.IRON_INGOT)+total(all,Items.IRON_INGOT)==4,"real inventory stock conserved");h.assertTrue(all.stream().allMatch(c->c.getInventory().countItem(Items.IRON_INGOT)==2),"both workers fetch two real ingots");});
    }

    private static final List<Bases.Base> haulBases=new ArrayList<>();
    @AfterBatch(batch="haul-routes") public static void haulCleanup(ServerLevel level){cleanup(level);Bases.get(level.getServer()).bases.removeAll(haulBases);haulBases.clear();Motor.clearSharedPaths();}
    @AfterBatch(batch="haul-load") public static void loadCleanup(ServerLevel level){haulCleanup(level);}
    @AfterBatch(batch="haul-bundle") public static void bundleCleanup(ServerLevel level){haulCleanup(level);}
    @AfterBatch(batch="haul-stale") public static void staleCleanup(ServerLevel level){haulCleanup(level);}
    @AfterBatch(batch="haul-food") public static void emergencyCleanup(ServerLevel level){haulCleanup(level);}
    private static net.minecraft.world.Container warehouse(GameTestHelper h,BlockPos local,Item item,int count){
        h.setBlock(local,Blocks.CHEST);var world=h.absolutePos(local);var chest=(net.minecraft.world.Container)h.getLevel().getBlockEntity(world);
        chest.setItem(0,new ItemStack(item,count));var bases=Bases.get(h.getLevel().getServer());var base=bases.add(h.getLevel().dimension(),world,"haul test");haulBases.add(base);bases.addChest(base,world);bases.record(h.getLevel().dimension(),world,chest);return chest;
    }
    @GameTest(template="arena",batch="haul-routes",timeoutTicks=80)
    public static void failureMemoryTracksNearbyChangesMovementExpiryAndReload(GameTestHelper h){
        arena(h);var level=h.getLevel();var from=h.absolutePos(new BlockPos(5,2,7));var target=from.east(6);var far=from.east(96);var oldFar=level.getBlockState(far);long now=level.getGameTime();
        var memory=new com.rlclones.ai.util.UnreachableMemory();
        try {
            memory.remember(level,from,target,now,80);h.assertTrue(memory.blocked(level,from,target,now),"unchanged failed route remembered");
            level.setBlock(far,oldFar.isAir()?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
            h.assertTrue(memory.blocked(level,from,target,now),"unrelated distant edits preserve failed route");
            h.setBlock(new BlockPos(8,2,7),Blocks.WATER);h.assertTrue(!memory.blocked(level,from,target,now),"local fluid/geometry change unlocks same tick retry");
            memory.remember(level,from,target,now,80);h.assertTrue(!memory.blocked(level,from.east(3),target,now),"different approach can retry");
            memory.remember(level,from,target,now,10);h.assertTrue(memory.blocked(level,from,target,now+9)&&!memory.blocked(level,from,target,now+10),"dynamic obstacles retried after expiry");
            memory.remember(level,from,target,now,80);Perception.invalidateClassifications();h.assertTrue(!memory.blocked(level,from,target,now),"tag reload invalidates failure knowledge");
            for(int i=0;i<12;i++)memory.remember(level,from,target.east(i),now,80);h.assertTrue(memory.size()<=8,"bounded records");
            memory.remember(level,from,target,now,80);h.assertTrue(!memory.blocked(level.getServer().getLevel(net.minecraft.world.level.Level.NETHER),from,target,now),"worlds never share failure");
        }finally{level.setBlock(far,oldFar,2);}h.succeed();
    }
    @GameTest(template="arena",batch="haul-routes",timeoutTicks=80)
    public static void failedRealPathProbeIsReusedUntilDoorwayOpens(GameTestHelper h){
        arena(h);Motor.clearSharedPaths();var c=actor(h,4,7);
        for(var p:BlockPos.betweenClosed(new BlockPos(10,2,5),new BlockPos(14,5,9)))
            h.setBlock(p,p.getX()==10||p.getX()==14||p.getZ()==5||p.getZ()==9||p.getY()==5?Blocks.STONE:Blocks.AIR);
        var goal=h.absoluteVec(new Vec3(12.5,2,7.5));var motor=c.controller().motor();
        h.assertTrue(motor.pathTo(goal)==null,"real closed room unreachable");int computed=Motor.pathsComputed;
        h.assertTrue(motor.pathTo(goal)==null&&Motor.pathsComputed==computed&&motor.pathsSkipped>0,"repeat path failure avoids another A-star search");
        h.setBlock(new BlockPos(10,2,7),Blocks.AIR);h.setBlock(new BlockPos(10,3,7),Blocks.AIR);
        h.assertTrue(motor.pathTo(goal)!=null&&Motor.pathsComputed>computed,"new doorway is usable immediately");h.succeed();
    }
    @GameTest(template="arena",batch="haul-load",timeoutTicks=300)
    public static void partialResourceLoadIsFilledFromTwoChestsBeforeOneDelivery(GameTestHelper h){
        arena(h);var recipient=actor(h,8,7);var courier=actor(h,6,7);courier.getInventory().setItem(0,new ItemStack(Items.IRON_INGOT,9));
        var first=warehouse(h,new BlockPos(6,2,10),Items.IRON_INGOT,5);first.setItem(1,new ItemStack(Items.DIAMOND,3));
        var second=warehouse(h,new BlockPos(9,2,10),Items.IRON_INGOT,20);
        com.rlclones.ai.civilization.CivicWork.post(recipient,"minecraft:iron_ingot",16);var work=courier.controller().civicWork();h.assertTrue(work.begin(),"partial load courier accepts full order");
        h.onEachTick(()->{work.tick();courier.controller().motor().tick();});
        h.succeedWhen(()->{h.assertTrue(work.completed==1,"single full delivery completes");h.assertTrue(work.toppedUpTrips==1&&recipient.getInventory().countItem(Items.IRON_INGOT)==16,"one surplus ingot topped up to sixteen");h.assertTrue(first.countItem(Items.IRON_INGOT)==0&&second.countItem(Items.IRON_INGOT)==10,"exact deficit fetched from both chests");h.assertTrue(courier.getInventory().countItem(Items.IRON_INGOT)==8&&first.countItem(Items.DIAMOND)==3,"own reserve and unrelated materials untouched");});
    }
    @GameTest(template="arena",batch="haul-bundle",timeoutTicks=240)
    public static void sameRecipientCarriedMaterialsShareOneTripAndCancelReleasesAllLeases(GameTestHelper h){
        arena(h);var recipient=actor(h,8,7);var courier=actor(h,6,7);courier.getInventory().setItem(0,new ItemStack(Items.IRON_INGOT,12));courier.getInventory().setItem(1,new ItemStack(Items.COAL,12));
        var manager=CloneManager.get(h.getLevel().getServer());
        com.rlclones.ai.civilization.CivicWork.post(recipient,"minecraft:iron_ingot",2);com.rlclones.ai.civilization.CivicWork.post(recipient,"minecraft:coal",3);
        var work=courier.controller().civicWork();h.assertTrue(work.begin(),"combined courier starts");
        var coalKey=com.rlclones.ai.civilization.CivicWork.key(recipient,"minecraft:coal");
        h.assertTrue(courier.getUUID().equals(manager.workBoard().find(coalKey,h.getLevel().getGameTime()).worker(h.getLevel().getGameTime())),"second compatible job reserved");
        work.cancel();h.assertTrue(manager.workBoard().find(coalKey,h.getLevel().getGameTime()).worker(h.getLevel().getGameTime())==null,"interruption releases bundled reservation");h.assertTrue(work.begin(),"combined work can restart");
        h.onEachTick(()->{work.tick();courier.controller().motor().tick();});
        h.succeedWhen(()->{h.assertTrue(work.completed==2&&work.bundledDeliveries==1,"both already-carried materials delivered in one trip");h.assertTrue(recipient.getInventory().countItem(Items.IRON_INGOT)==2&&recipient.getInventory().countItem(Items.COAL)==3,"requested quantities received");h.assertTrue(courier.getInventory().countItem(Items.IRON_INGOT)==10&&courier.getInventory().countItem(Items.COAL)==9,"exact conservation across actual pickups");});
    }
    @GameTest(template="arena",batch="haul-food",timeoutTicks=80)
    public static void carriedEmergencyFoodIsDeliveredWithoutWaitingForWarehouse(GameTestHelper h){
        arena(h);var recipient=actor(h,8,7);var courier=actor(h,6,7);courier.getInventory().setItem(0,new ItemStack(Items.BREAD,5));var chest=warehouse(h,new BlockPos(6,2,10),Items.BREAD,16);
        com.rlclones.ai.civilization.CivicWork.post(recipient,com.rlclones.ai.civilization.WorkBoard.FOOD,4);var work=courier.controller().civicWork();h.assertTrue(work.begin(),"urgent food accepted");work.tick();
        h.assertTrue(courier.getInventory().countItem(Items.BREAD)==4&&chest.countItem(Items.BREAD)==16,"available emergency food handed over before any warehouse trip");work.cancel();h.succeed();
    }
    @GameTest(template="arena",batch="haul-stale",timeoutTicks=160)
    public static void staleWarehouseSnapshotsDoNotLoopOrCreateCargo(GameTestHelper h){
        arena(h);var recipient=actor(h,8,7);var courier=actor(h,6,7);var first=warehouse(h,new BlockPos(6,2,10),Items.IRON_INGOT,4);var second=warehouse(h,new BlockPos(9,2,10),Items.IRON_INGOT,4);first.clearContent();second.clearContent();
        com.rlclones.ai.civilization.CivicWork.post(recipient,"minecraft:iron_ingot",8);var work=courier.controller().civicWork();h.assertTrue(work.begin(),"courier initially knows stale stock");boolean[] ended={false};
        h.onEachTick(()->{if(!ended[0])ended[0]=!work.tick();courier.controller().motor().tick();});
        h.succeedWhen(()->{h.assertTrue(ended[0],"empty sources terminate the delivery attempt");h.assertTrue(work.completed==0&&total(List.of(recipient,courier),Items.IRON_INGOT)==0,"no cargo is fabricated from remembered stock");});
    }

    @AfterBatch(batch="haul-full") public static void fullHaulCleanup(ServerLevel level){haulCleanup(level);}
    @GameTest(template="arena",batch="haul-full",timeoutTicks=240)
    public static void fullBagCanTopUpItsExistingStackWithoutOverdrawing(GameTestHelper h){
        arena(h);var recipient=actor(h,8,7);var courier=actor(h,6,7);
        for(int i=0;i<courier.getInventory().items.size();i++)courier.getInventory().setItem(i,new ItemStack(Items.COBBLESTONE,64));
        courier.getInventory().setItem(0,new ItemStack(Items.IRON_INGOT,9));var chest=warehouse(h,new BlockPos(6,2,10),Items.IRON_INGOT,20);
        com.rlclones.ai.civilization.CivicWork.post(recipient,"minecraft:iron_ingot",16);var work=courier.controller().civicWork();h.assertTrue(work.begin(),"full bag accepts stack top-up");
        h.onEachTick(()->{work.tick();courier.controller().motor().tick();});
        h.succeedWhen(()->{h.assertTrue(work.completed==1&&recipient.getInventory().countItem(Items.IRON_INGOT)==16,"existing partial stack filled without an empty slot");h.assertTrue(chest.countItem(Items.IRON_INGOT)==5&&courier.getInventory().countItem(Items.IRON_INGOT)==8,"exact deficit and courier reserve conserved");h.assertTrue(courier.getInventory().countItem(Items.COBBLESTONE)==35*64,"other full stacks retained");});
    }

    @AfterBatch(batch="mining-exclusive") public static void miningCleanup(ServerLevel level){BlockClaims.forgetAll();cleanup(level);}
    private static void miningLook(ClonePlayer c,BlockPos p){
        var delta=Vec3.atCenterOf(p).subtract(c.getEyePosition());float yaw=(float)Math.toDegrees(Math.atan2(delta.z,delta.x))-90;float pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z)));
        c.setYRot(yaw);c.setYHeadRot(yaw);c.setXRot(pitch);c.getInventory().setItem(0,new ItemStack(Items.DIAMOND_PICKAXE));c.getInventory().selected=0;c.setOnGround(true);
    }
    @GameTest(template="arena",batch="mining-exclusive",timeoutTicks=60)
    public static void allMotorMiningHonorsExclusiveOwnerAndAllowsOtherBlocks(GameTestHelper h){
        arena(h);BlockClaims.forgetAll();var a=actor(h,6,7);var b=actor(h,6,8);var p=h.absolutePos(new BlockPos(8,3,7));var other=h.absolutePos(new BlockPos(8,3,8));h.setBlock(new BlockPos(8,3,7),Blocks.OBSIDIAN);h.setBlock(new BlockPos(8,3,8),Blocks.OBSIDIAN);
        miningLook(a,p);a.controller().motor().mine(p);long now=h.getLevel().getGameTime();
        h.assertTrue(BlockClaims.takenByOther(h.getLevel(),p,b.getUUID(),now),"first real Motor miner owns block");
        miningLook(b,p);h.assertTrue(!b.controller().motor().mine(p)&&b.controller().motor().mineDebug.contains("occupied"),"second subsystem cannot start breaking same block");
        h.assertTrue(!b.controller().safeToDig(p),"candidate selection ignores another miner");
        miningLook(b,other);b.controller().motor().mine(other);h.assertTrue(BlockClaims.takenByOther(h.getLevel(),other,a.getUUID(),now),"another block remains available");
        a.controller().motor().resetMining();miningLook(b,p);b.controller().motor().mine(p);h.assertTrue(BlockClaims.takenByOther(h.getLevel(),p,a.getUUID(),now),"interrupted block immediately inherited");h.succeed();
    }
    @GameTest(template="arena",batch="mining-exclusive",timeoutTicks=60)
    public static void blockClaimsExpireReleaseOnDeathAndIsolateWorlds(GameTestHelper h){
        arena(h);BlockClaims.forgetAll();var a=actor(h,6,7);var b=actor(h,6,8);var p=h.absolutePos(new BlockPos(8,3,7));h.setBlock(new BlockPos(8,3,7),Blocks.OBSIDIAN);long now=h.getLevel().getGameTime();
        h.assertTrue(BlockClaims.claim(h.getLevel(),p,a.getUUID(),now),"planning claim");
        h.assertTrue(!BlockClaims.takenByOther(h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER),p,b.getUUID(),now),"same coordinates in another world independent");
        h.assertTrue(!BlockClaims.takenByOther(h.getLevel(),p,b.getUUID(),now+61),"abandoned planning claim expires");
        miningLook(a,p);a.controller().motor().mine(p);h.assertTrue(!BlockClaims.takenByOther(h.getLevel(),p,b.getUUID(),now+3),"abandoned active mining expires quickly");
        a.controller().motor().mine(p);a.setHealth(0);h.assertTrue(!BlockClaims.takenByOther(h.getLevel(),p,b.getUUID(),now),"dead miner no longer owns block");h.succeed();
    }
    @GameTest(template="arena",batch="mining-exclusive",timeoutTicks=60)
    public static void realHumanMiningPreemptsCloneAndAbortReleasesClaim(GameTestHelper h){
        arena(h);BlockClaims.forgetAll();var clone=actor(h,6,7);var p=h.absolutePos(new BlockPos(8,3,7));h.setBlock(new BlockPos(8,3,7),Blocks.OBSIDIAN);miningLook(clone,p);clone.controller().motor().mine(p);
        var server=h.getLevel().getServer();var profile=new com.mojang.authlib.GameProfile(UUID.randomUUID(),"MiningHuman");
        var human=new net.minecraft.server.level.ServerPlayer(server,h.getLevel(),profile);
        human.connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(server,new CloneConnection(),human);
        human.setPos(clone.getX(),clone.getY(),clone.getZ());human.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);human.getInventory().setItem(0,new ItemStack(Items.DIAMOND_PICKAXE));human.getInventory().selected=0;
        server.getPlayerList().op(profile);
        try {
            human.gameMode.handleBlockBreakAction(p,net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,Direction.UP,h.getLevel().getMaxBuildHeight(),0);
            long now=h.getLevel().getGameTime();h.assertTrue(BlockClaims.takenByOther(h.getLevel(),p,clone.getUUID(),now),"accepted vanilla human digging takes priority");
            h.assertTrue(!clone.controller().motor().mine(p)&&clone.controller().motor().mineDebug.contains("occupied"),"clone yields without interfering with human");
            human.gameMode.handleBlockBreakAction(p,net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,Direction.UP,h.getLevel().getMaxBuildHeight(),1);
            h.assertTrue(!BlockClaims.takenByOther(h.getLevel(),p,clone.getUUID(),now),"human abort immediately releases block");
            clone.controller().motor().mine(p);h.assertTrue(BlockClaims.takenByOther(h.getLevel(),p,human.getUUID(),now),"clone can resume after human leaves");
            human.gameMode.handleBlockBreakAction(p,net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,Direction.UP,h.getLevel().getMaxBuildHeight(),2);
            human.setHealth(0);human.gameMode.tick();h.assertTrue(!BlockClaims.takenByOther(h.getLevel(),p,clone.getUUID(),now),"dead human releases mining state");
        } finally {BlockClaims.humanStopped(human.getUUID());server.getPlayerList().deop(profile);human.discard();}h.succeed();
    }

    @AfterBatch(batch="work-access") public static void accessCleanup(ServerLevel level){cleanup(level);}
    @GameTest(template="arena",batch="work-access",timeoutTicks=200)
    public static void opensLowObstructionAndActuallyCollectsTheItem(GameTestHelper h){
        arena(h);var c=actor(h,6,7);c.getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));c.getInventory().selected=0;h.setBlock(new BlockPos(8,2,7),Blocks.STONE);for(int x=4;x<=15;x++){h.setBlock(new BlockPos(x,4,7),Blocks.BEDROCK);for(int y=2;y<=4;y++){h.setBlock(new BlockPos(x,y,6),Blocks.BEDROCK);h.setBlock(new BlockPos(x,y,8),Blocks.BEDROCK);}}
        var point=h.absoluteVec(new Vec3(12.5,2.2,7.5));var item=new net.minecraft.world.entity.item.ItemEntity(h.getLevel(),point.x,point.y,point.z,new ItemStack(Items.COAL,3));item.setNoPickUpDelay();h.getLevel().addFreshEntity(item);
        h.onEachTick(()->{var motor=c.controller().motor();if(!motor.clearWorkObstruction(item.position().add(0,.2,0),null))motor.navigate(item.position(),.5,false);motor.tick();});
        h.succeedWhen(()->{h.assertTrue(c.controller().motor().workBlocksCleared>0&&h.getBlockState(new BlockPos(8,2,7)).isAir(),"blocking step dug instead of jumping into low ceiling");h.assertTrue(c.getInventory().countItem(Items.COAL)==3,"actual item recovered through opened path");});
    }
    @GameTest(template="arena",batch="work-access",timeoutTicks=40)
    public static void earlyUtilityToolsWaitForFullEquippedNetherite(GameTestHelper h){
        arena(h);var c=actor(h,6,7);
        for(var item:new Item[]{Items.WOODEN_HOE,Items.STONE_HOE,Items.WOODEN_SHOVEL,Items.STONE_SHOVEL})h.assertTrue(Crafting.deferEarlyTool(c,item)&&Crafting.recipesFor(c,s->s.is(item)).isEmpty(),"early utility tool blocked in recipe planner");
        var protectedCell=new BlockPos(8,2,7);h.setBlock(protectedCell,Blocks.STONECUTTER);h.assertTrue(!c.controller().motor().canClearWorkBlock(h.absolutePos(protectedCell)),"functional equipment is never treated as a natural obstruction");
        h.assertTrue(!Crafting.deferEarlyTool(c,Items.IRON_SHOVEL)&&!Crafting.deferEarlyTool(c,Items.STONE_PICKAXE),"better tools and essential pickaxes stay available");
        c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,new ItemStack(Items.NETHERITE_HELMET));c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,new ItemStack(Items.NETHERITE_CHESTPLATE));c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS,new ItemStack(Items.NETHERITE_LEGGINGS));c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,new ItemStack(Items.NETHERITE_BOOTS));
        h.assertTrue(!Crafting.deferEarlyTool(c,Items.STONE_HOE)&&!Crafting.recipesFor(c,s->s.is(Items.STONE_HOE)).isEmpty(),"full equipped netherite unlocks low utility recipes");h.succeed();
    }

    private static Bases.Staircase accessStair;
    @AfterBatch(batch="access-repair") public static void accessRepairCleanup(ServerLevel level){cleanup(level);Bases.get(level.getServer()).staircases.remove(accessStair);accessStair=null;}
    @GameTest(template="arena",batch="access-repair",timeoutTicks=220)
    public static void temporaryTreadAccessStaysOpenUntilWorkCompletesThenRestores(GameTestHelper h){
        arena(h);var c=actor(h,5,7);c.getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));c.getInventory().selected=0;c.getInventory().add(new ItemStack(Items.COBBLESTONE,8));var bases=Bases.get(h.getLevel().getServer());
        var top=h.absolutePos(new BlockPos(7,4,7));accessStair=bases.addStaircase(h.getLevel().dimension(),top,Direction.EAST);accessStair.end=top.east(2).below(2);var tread=top.below();h.getLevel().setBlock(tread,Blocks.STONE.defaultBlockState(),3);h.getLevel().setBlock(tread.below(),Blocks.STONE.defaultBlockState(),3);
        var destination=h.absoluteVec(new Vec3(9.5,3.5,7.5));boolean[] opened={false};
        h.onEachTick(()->{var motor=c.controller().motor();if(!opened[0]){motor.clearWorkObstruction(destination,null);if(h.getLevel().getBlockState(tread).isAir()){
            h.assertTrue(motor.deferredWorkRepairs.contains(tread)&&!motor.repairs.contains(tread),"cut step remains open during access task");c.controller().scanTreads();h.assertTrue(!motor.repairs.contains(tread),"periodic repair cannot reseal active access");motor.repairs.clear();motor.finishWorkAccess();opened[0]=true;}}
            else c.controller().repairTreads(h.getLevel().getGameTime());motor.tick();});
        h.succeedWhen(()->{h.assertTrue(opened[0]&&h.getLevel().getBlockState(tread).is(Blocks.COBBLESTONE),"actual temporary step opening restored after work");h.assertTrue(c.getInventory().countItem(Items.COBBLESTONE)==7,"one real block consumed in restoration");});
    }


    @GameTest(template="arena",batch="indexed-recipes",timeoutTicks=40)
    public static void exactRecipeIndexPreservesOrderAndReload(GameTestHelper h) {
        arena(h);
        var c = actor(h,5,7);
        var manager = h.getLevel().getRecipeManager();
        var original = new ArrayList<Recipe<?>>(manager.getRecipes());
        var sample = List.of(Items.STICK, Items.FURNACE, Items.CRAFTING_TABLE, Items.OAK_BOAT,
                Items.WOODEN_HOE, Items.STONE_SHOVEL, Items.IRON_PICKAXE, Items.CHAINMAIL_BOOTS, Items.AIR);
        try {
            Crafting.invalidateRecipes();
            for (Item item : sample) {
                h.assertTrue(Crafting.recipesFor(c, s -> s.is(item)).equals(Crafting.recipesForItem(c,item)),
                        "exact index matches full scan: " + item);
            }
            var custom = new ResourceLocation("coop_test","indexed_copper_boots");
            var ingredients = NonNullList.withSize(1,Ingredient.of(Items.COPPER_INGOT));
            var mixed = new ArrayList<>(original);
            mixed.add(new ShapedRecipe(custom,"",CraftingBookCategory.EQUIPMENT,1,1,ingredients,new ItemStack(Items.CHAINMAIL_BOOTS)));
            manager.replaceRecipes(mixed);
            Crafting.invalidateRecipes();
            var indexed = Crafting.recipesForItem(c,Items.CHAINMAIL_BOOTS);
            h.assertTrue(indexed.equals(Crafting.recipesFor(c,s -> s.is(Items.CHAINMAIL_BOOTS)))
                    && indexed.stream().anyMatch(recipe -> recipe.getId().equals(custom)), "reload keeps custom recipes and order");
            manager.replaceRecipes(original);
            Crafting.invalidateRecipes();
            h.assertTrue(Crafting.recipesForItem(c,Items.CHAINMAIL_BOOTS).stream().noneMatch(recipe -> recipe.getId().equals(custom)),
                    "removed recipe is no longer indexed");
        } finally {
            manager.replaceRecipes(original);
            Crafting.invalidateRecipes();
            cleanup(h.getLevel());
        }
        h.succeed();
    }

    @GameTest(template="arena",batch="inventory-scan-equivalence",timeoutTicks=40)
    public static void consolidatedGearScansMatchLegacyAndSeeLiveChanges(GameTestHelper h) {
        arena(h);
        var c=actor(h,5,7);
        var inv=c.getInventory();
        Item[][] kinds={{Items.DIAMOND_PICKAXE,Items.IRON_PICKAXE,Items.STONE_PICKAXE,Items.WOODEN_PICKAXE},
                {Items.DIAMOND_AXE,Items.IRON_AXE,Items.STONE_AXE,Items.WOODEN_AXE},
                {Items.DIAMOND_SWORD,Items.IRON_SWORD,Items.STONE_SWORD,Items.WOODEN_SWORD}};
        Item[] armor={Items.DIAMOND_HELMET,Items.DIAMOND_CHESTPLATE,Items.DIAMOND_LEGGINGS,Items.DIAMOND_BOOTS,
                Items.IRON_HELMET,Items.IRON_CHESTPLATE,Items.IRON_LEGGINGS,Items.IRON_BOOTS};
        var pool=new ArrayList<Item>();
        for(Item[] kind:kinds)Collections.addAll(pool,kind);
        Collections.addAll(pool,armor);
        Collections.addAll(pool,Items.LEATHER_BOOTS,Items.CHAINMAIL_HELMET,Items.NETHERITE_CHESTPLATE,Items.STICK);
        var random=new Random(10824);
        try {
            for(int trial=0;trial<100;trial++){
                inv.clearContent();
                for(int i=0;i<inv.items.size();i++){
                    if(random.nextBoolean())continue;
                    ItemStack stack=new ItemStack(pool.get(random.nextInt(pool.size())));
                    if(stack.isDamageableItem()&&random.nextBoolean())stack.setDamageValue(stack.getMaxDamage()-1);
                    inv.items.set(i,stack);
                }
                if(trial%3==0)c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,new ItemStack(Items.DIAMOND_HELMET));
                if(trial%5==0)c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,new ItemStack(Items.NETHERITE_CHESTPLATE));
                var expectedArmor=new ArrayList<Item>();
                for(Item item:armor)if(Crafting.armorImproves(c,new ItemStack(item)))expectedArmor.add(item);
                var actualArmor=new ArrayList<Item>();
                Crafting.addArmorUpgrades(c,actualArmor);
                h.assertTrue(expectedArmor.equals(actualArmor),"armor order and scores agree at trial "+trial);
                var expected=new ArrayList<Item>(List.of(Items.STICK));
                for(Item[] kind:kinds)for(Item item:kind){
                    boolean has=false,sound=false;
                    for(ItemStack stack:inv.items)if(stack.is(item)){has=true;sound|=!Equipment.worn(stack);}
                    if(has){if(!sound)expected.add(0,item);break;}
                }
                var actual=new ArrayList<Item>(List.of(Items.STICK));
                Crafting.addWornReplacements(c,actual);
                h.assertTrue(expected.equals(actual),"worn gear order agrees at trial "+trial);
            }
            inv.clearContent();
            var pick=new ItemStack(Items.DIAMOND_PICKAXE);
            inv.items.set(0,pick);
            var replacements=new ArrayList<Item>();
            Crafting.addWornReplacements(c,replacements);
            h.assertTrue(replacements.isEmpty(),"sound tool is not replaced");
            pick.setDamageValue(pick.getMaxDamage()-1);
            Crafting.addWornReplacements(c,replacements);
            h.assertTrue(replacements.equals(List.of(Items.DIAMOND_PICKAXE)),"direct mutation of existing stack is visible immediately");
        } finally {cleanup(h.getLevel());}
        h.succeed();
    }
}
