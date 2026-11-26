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

}
