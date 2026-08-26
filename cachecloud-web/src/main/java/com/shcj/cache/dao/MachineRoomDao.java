package com.shcj.cache.dao;

import com.shcj.cache.entity.MachineRoom;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Created by chenshi on 2018/10/16.
 */
public interface MachineRoomDao {

    @Select("select * from machine_room where status=1")
    List<MachineRoom> getEffectiveRoom();

    @Select("select * from machine_room")
    List<MachineRoom> getAllRoom();

    @Insert("insert into machine_room(id, name, status, `desc`, ip_network, operator) values (#{id},#{name},#{status},#{desc},#{ipNetwork},#{operator})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int saveRoom(MachineRoom room);

    @Update("update machine_room set name=#{name}, status=#{status}, `desc`=#{desc}, ip_network=#{ipNetwork}, operator=#{operator} where id=#{id}")
    int updateRoom(MachineRoom room);

    @Delete("delete from machine_room where id=#{id}")
    void removeRoom(long id);
}
