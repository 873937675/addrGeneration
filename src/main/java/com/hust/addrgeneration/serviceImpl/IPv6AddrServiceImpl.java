package com.hust.addrgeneration.serviceImpl;

import com.alibaba.fastjson.JSONObject;
import com.hust.addrgeneration.beans.*;
import com.hust.addrgeneration.dao.UserMapper;
import com.hust.addrgeneration.service.IPv6AddrService;
import com.hust.addrgeneration.utils.AddressUtils;
import com.hust.addrgeneration.utils.ConvertUtils;
import com.hust.addrgeneration.utils.EncDecUtils;
import com.hust.addrgeneration.utils.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.*;
import java.util.regex.Pattern;

@Service
public class IPv6AddrServiceImpl implements IPv6AddrService {
    private final UserMapper userMapper;
    private static final Logger logger = LoggerFactory.getLogger(IPv6AddrServiceImpl.class);
    private ISP ispPrefix;

    @Autowired
    public IPv6AddrServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public ResponseEntity<UserResponse> registerNID(User infoBean) {
        UserResponse response = new UserResponse();

        User user = infoBean;
        String userID = user.getUserID();
        String password = user.getPassword();
        String phoneNumber = user.getPhoneNumber();
        String userName = user.getUsername();


        // step0. Check phoneNumber validation
        String phoneRegexp = "^((13[0-9])|(14[57])|(15[0-35-9])|(16[2567])|(17[0-8])|(18[0-9])|(19[0-9]))\\d{8}$";
        if(!Pattern.matches(phoneRegexp,phoneNumber)){
            response.responseError(10012);
        }

        User checkUser = userMapper.queryPhoneNumber(phoneNumber);
        if(checkUser != null ){
            response.responseError(10015);
        }

        // step1. Calculate nid with user's information
        String encryptStr = userID + phoneNumber + userName;
        String hashStr = HashUtils.SM3Hash(encryptStr);
        String userPart = ConvertUtils.hexStringToBinString(hashStr).substring(0,38);
        String userType = userID.substring(0,1);
        String organizationPart = "";
        switch (userType) {
            case "U" :
                organizationPart = "00";
                break;

            case "M" :
                organizationPart = "01";
                break;

            case "D" :
                organizationPart = "10";
                break;
            default:
                organizationPart = "11";
                break;
        }

        String nid = ConvertUtils.binStringToHexString(userPart + organizationPart);
        user.setNid(nid);
        try{
            userMapper.register(nid,password,userID,phoneNumber, userName);
        } catch (Exception e) {
            response.responseError(10002);
        }
        try{
            GenerateAddress addressInfo = new GenerateAddress();
            addressInfo.setPhoneNumber(user.getPhoneNumber());
            addressInfo.setPassword(user.getPassword());
            this.createAddr(addressInfo);
        } catch (Exception e){
            response.responseError(10003);
        }
        response.setCode(0);
        response.setMsg("Success");
        response.setUser(user);
        return new ResponseEntity<UserResponse>(response, HttpStatus.OK);
    }

    @Override
    public ResponseEntity<GenerateAddressResponse> createAddr(GenerateAddress addressInfo) throws Exception {
        GenerateAddressResponse response = new GenerateAddressResponse();

        String phoneNumber = addressInfo.getPhoneNumber();
        String password = addressInfo.getPassword();
        String prefix = ispPrefix.getIsp();

        User user = userMapper.queryPhoneNumber(phoneNumber);
        if(user==null){
            response.responseError(10004);
        }

        String nid = user.getNid();

        // 如果没有ISP
        if(prefix == null || prefix.isEmpty()){
            response.responseError(10018);
        }

        // step0. check if address is applied
        String address = userMapper.queryAIDTrunc(nid);
        if(address != null){
            response.responseError(10011);
        }

        // step1. check nid and password
        /*
         if the nid isn't in the database, return the information tells user to register a nid
         if the nid isn't match the password, return the wrong password information
         */
        try{
            userMapper.queryRegisterInfo(nid);
        } catch (Exception e){
            response.responseError(10017);
        }

        String passwordFromDB = userMapper.queryRegisterPassword(nid);
        if (!passwordFromDB.equals(password)) {
            response.responseError(10005);
        }

        // step2. Calculate the time information
        LocalDateTime localDateTime1 = LocalDateTime.now();
        LocalDateTime localDateTime2 = LocalDateTime.of(localDateTime1.getYear(), 1, 1, 0, 0, 0);

        long currentTime = localDateTime1.toEpochSecond(ZoneOffset.of("+8"));
        long baseTime = localDateTime2.toEpochSecond(ZoneOffset.of("+8"));

        int timeDifference = (int) (( currentTime - baseTime ) / 10);
        String timeInformation = ConvertUtils.decToBinString(timeDifference, 24);
        String rawAID = nid + ConvertUtils.binStringToHexString(timeInformation);

        // step3. Generate AID-noTimeHash(aka AID_nTH) with UID and time information
        String preAID = EncDecUtils.ideaEncrypt(rawAID, EncDecUtils.ideaKey);
        if(preAID == null || preAID.length() != 32){
            response.responseError(10009);
        }
        logger.info(preAID);
        String str1 = preAID.substring(0,16);
        String str2 = preAID.substring(16,32);

        BigInteger big1 = new BigInteger(str1, 16);
        BigInteger big2 = new BigInteger(str2, 16);
        String AIDnTH = big1.xor(big2).toString(16);

        try{
            userMapper.updateAIDnTH(AIDnTH, big1.toString(16));
        } catch (Exception e) {
            response.responseError(10003);
        }
        // step4. Generate AID-withTimeHash(aka AID) with AIDnTH and time-Hash
        LocalDateTime localDateTime3 = LocalDateTime.of(localDateTime1.getYear(),localDateTime1.getMonth(),localDateTime1.getDayOfMonth(),localDateTime1.getHour(),0,0);
        long nearestTimeHour = localDateTime3.toEpochSecond(ZoneOffset.of("+8"));
        int timeDifference2 = (int) (nearestTimeHour - baseTime);
        String timeHash = EncDecUtils.md5Encrypt16(ConvertUtils.decToHexString(timeDifference2,10));

        BigInteger big3 = new BigInteger(AIDnTH,16);
        BigInteger big4 = new BigInteger(timeHash, 16);
        String AID = big3.xor(big4).toString(16);
        try{
            userMapper.updateAID(AID, AIDnTH);
        } catch (Exception e){
            response.responseError(10003);
        }

        // step5. Trunc AID with given prefix length and store to database
        int prefixLength = AddressUtils.getAddressBitLength(prefix) / 4;
        String visibleAID = AID.substring(0, 16 - prefixLength);
        String hiddenAID = AID.substring(16 - prefixLength);
        String prefix64bits = prefix.replace(":","") + visibleAID;
        StringBuilder prefix64 = new StringBuilder();
        for (int i = 0; i < prefix64bits.length(); i+=4) {
            prefix64.append(prefix64bits, i, i + 4).append(":");
        }
        prefix64.deleteCharAt(prefix64.length() - 1);
        String generateAddr = String.valueOf(prefix64);
        try{
            userMapper.updateAIDTrunc(generateAddr.replace(":",""), visibleAID, hiddenAID, timeDifference, nid, currentTime, prefix);
        } catch (Exception e){
            response.responseError(10003);
        }
        response.setCode(0);
        response.setMsg("success");
        response.setAddress(generateAddr);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }


    @Override
    public ResponseEntity<QueryAddressResponse> queryAddr(QueryAddress queryAddressInfo) throws Exception {
        QueryAddressResponse response = new QueryAddressResponse();

        // step1. revert AID
        String queryAddress = queryAddressInfo.getQueryAddress().replace(":","");
        int timeDifference = 0;
        try{
            timeDifference = userMapper.queryAIDTruncTime(queryAddress);
        } catch (Exception e) {
            response.responseError(10016);
        }
        int prefixLength = ispPrefix.getLength();
        String visibleAID = queryAddress.substring(prefixLength,16);
        String hiddenAID = userMapper.queryAIDTruncHiddenAID(visibleAID,timeDifference);
        String AID = visibleAID + hiddenAID;
        // step2. use prefix of the IPv6-address and calculate time-Hash to get key
        String asPrefix = "2001:250:4000:4507";
        String asAddress = asPrefix + "::1";
        String AIDnTH = userMapper.queryAIDAIDnTH(AID);
        BigInteger big1 = new BigInteger(AID, 16);
        BigInteger big2 = new BigInteger(AIDnTH, 16);
        String timeHash = big1.xor(big2).toString(16);

        // step3. use suffix of IPv6-address to get the whole encrypt data(128-bits)
        String prefix = userMapper.queryAIDnTHPrefix(AIDnTH);
        BigInteger big3 = new BigInteger(AIDnTH, 16);
        BigInteger big4 = new BigInteger(prefix, 16);
        String suffix = big3.xor(big4).toString(16);
        String preAID = prefix + suffix;

        // step4. use the proper key to decrypt the encrypt data(128-bits)
        String ideakey = userMapper.getIdeaKey(timeHash, asAddress);
        if (ideakey == null){
            response.responseError(10007);
        }
        String rawAID = EncDecUtils.ideaDecrypt(preAID, ideakey);
        if (rawAID == null || rawAID.length() != 16) {
            response.responseError(10008);
        }

        // step5. use the nid to query user information the return the info(userID, phoneNumber, address-generate-time etc.) to user
        String nid = rawAID.substring(0,10);
        String timeInfoStr = ConvertUtils.hexStringToBinString(rawAID.substring(10));
        int timeInfo = Integer.parseInt(timeInfoStr, 2) * 10;
        LocalDateTime localDateTime2 = LocalDateTime.of(LocalDate.now().getYear(), 1, 1, 0, 0, 0);
        long baseTime = localDateTime2.toEpochSecond(ZoneOffset.of("+8"));
        long registerTime = (baseTime + timeInfo);
        Instant instant = Instant.ofEpochSecond(registerTime);
        LocalDateTime registerTimeStr = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());

        User userInfo = userMapper.queryRegisterInfo(nid);
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("userID", userInfo.getUserID());
        jsonObject.put("phoneNumber", userInfo.getPhoneNumber());
        jsonObject.put("registerTime", registerTimeStr.toString());
        jsonObject.put("username", userInfo.getUsername());

        response.setCode(0);
        response.setMsg("success");
        response.setInfo(jsonObject);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Response> updateISP(ISP isp) throws Exception{
        Response response = new Response();
        String ispStr = isp.getIsp();
        if(ispStr.contains("::/")) {
            ispStr = ispStr.substring(0, ispStr.indexOf("::/"));
        }
        int length = AddressUtils.getAddressBitLength(ispStr);
        ispPrefix.setIsp(isp.getIsp());
        ispPrefix.setLength(length);
        response.setCode(0);
        response.setMsg("success");
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @Override
    public ResponseEntity<ISPResponse> getISP() throws Exception{
        ISPResponse response = new ISPResponse();
        response.setIsp(ispPrefix.getIsp());
        response.setLength(ispPrefix.getLength());
        response.setCode(0);
        response.setMsg("success");
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
