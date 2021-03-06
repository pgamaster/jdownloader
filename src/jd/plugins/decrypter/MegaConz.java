package jd.plugins.decrypter;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Base64;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;

import org.appwork.utils.StringUtils;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "mega.co.nz" }, urls = { "(?:https?://(www\\.)?mega\\.(co\\.)?nz/[^/:]*#F|chrome://mega/content/secure\\.html#F|mega:///#F)(!|%21)[a-zA-Z0-9]+(!|%21)[a-zA-Z0-9_,\\-]{16,}((!|%21)[a-zA-Z0-9]+)?" }, flags = { 0 })
public class MegaConz extends PluginForDecrypt {

    private static AtomicLong CS = new AtomicLong(System.currentTimeMillis());

    public MegaConz(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static class MegaFolder {

        public String  parent;
        public String  name;
        private String id;

        public MegaFolder(String nodeID) {
            id = nodeID;
        }

    }

    private String getParentNodeID(CryptedLink link) {
        final String ret = new Regex(link.getCryptedUrl(), "(!|%21)([a-zA-Z0-9]+)$").getMatch(1);
        final String folderID = getFolderID(link);
        final String masterKey = getMasterKey(link);
        if (ret != null && !ret.equals(folderID) && !ret.equals(masterKey)) {
            return ret;
        }
        return null;
    }

    @Override
    public ArrayList<DownloadLink> decryptIt(CryptedLink parameter, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        parameter.setCryptedUrl(parameter.toString().replaceAll("%21", "!"));

        final String folderID = getFolderID(parameter);
        final String masterKey = getMasterKey(parameter);
        final String parentNodeID = getParentNodeID(parameter);
        final String containerURL;
        if (parameter.getCryptedUrl().startsWith("chrome://")) {
            if (folderID != null && masterKey != null) {
                containerURL = "https://mega.co.nz/#F!" + folderID + "!" + masterKey;
            } else {
                containerURL = parameter.getCryptedUrl();
            }
        } else {
            containerURL = parameter.getCryptedUrl();
        }
        try {
            br.setLoadLimit(16 * 1024 * 1024);
        } catch (final Throwable e) {
        }
        br.getHeaders().put("APPID", "JDownloader");
        br.postPageRaw("https://eu.api.mega.co.nz/cs?id=" + CS.incrementAndGet() + "&n=" + folderID, "[{\"a\":\"f\",\"c\":\"1\",\"r\":\"1\"}]");
        final String nodes[] = br.getRegex("\\{\\s*?(\"h\".*?)\\}").getColumn(0);
        /*
         * p = parent node (ID)
         * 
         * s = size
         * 
         * t = type (0=file, 1=folder, 2=root, 3=inbox, 4=trash
         * 
         * ts = timestamp
         * 
         * h = node (ID)
         * 
         * u = owner
         * 
         * a = attribute (contains name)
         * 
         * k = node key
         */
        final HashMap<String, MegaFolder> folders = new HashMap<String, MegaFolder>();
        for (final String node : nodes) {
            final String encryptedNodeKey = new Regex(getField("k", node), ":(.*?)$").getMatch(0);
            if (encryptedNodeKey == null) {
                continue;
            }
            String nodeAttr = getField("a", node);
            final String nodeID = getField("h", node);
            final String nodeParentID = getField("p", node);
            String nodeKey = decryptNodeKey(encryptedNodeKey, masterKey);

            nodeAttr = decrypt(nodeAttr, nodeKey);
            if (nodeAttr == null) {
                continue;
            }
            final String nodeName = removeEscape(new Regex(nodeAttr, "\"n\"\\s*?:\\s*?\"(.*?)(?<!\\\\)\"").getMatch(0));
            final String nodeType = getField("t", node);
            if ("1".equals(nodeType)) {
                /* folder */
                MegaFolder fo = new MegaFolder(nodeID);
                fo.parent = nodeParentID;
                fo.name = nodeName;
                folders.put(nodeID, fo);
            } else if ("0".equals(nodeType)) {
                if (parentNodeID != null && !StringUtils.equals(nodeParentID, parentNodeID)) {
                    continue;
                }
                /* file */
                final MegaFolder folder = folders.get(nodeParentID);
                final FilePackage fp = FilePackage.getInstance();
                final String path = getRelPath(folder, folders);
                fp.setName(path.substring(1));
                fp.setProperty("ALLOW_MERGE", true);
                final String nodeSize = getField("s", node);
                if (nodeSize == null) {
                    continue;
                }
                final String safeNodeKey = nodeKey.replace("+", "-").replace("/", "_");
                final DownloadLink link = createDownloadlink("http://mega.co.nz/#N!" + nodeID + "!" + safeNodeKey);
                if (folderID != null) {
                    // folder nodes can only be downloaded with knowledge of the folderNodeID
                    link.setProperty("public", false);
                    link.setProperty("pn", folderID);
                    if (safeNodeKey.endsWith("=")) {
                        link.setContentUrl("http://mega.co.nz/#N!" + nodeID + "!" + safeNodeKey + "###n=" + folderID);
                    } else {
                        link.setContentUrl("http://mega.co.nz/#N!" + nodeID + "!" + safeNodeKey + "=###n=" + folderID);
                    }
                }
                link.setContainerUrl(containerURL);
                link.setFinalFileName(nodeName);

                link.setProperty(DownloadLink.RELATIVE_DOWNLOAD_FOLDER_PATH, path);
                link.setAvailable(true);
                try {
                    link.setVerifiedFileSize(Long.parseLong(nodeSize));
                } catch (final Throwable e) {
                    link.setDownloadSize(Long.parseLong(nodeSize));
                }
                if (fp != null) {
                    fp.add(link);
                }
                decryptedLinks.add(link);
            }
        }
        return decryptedLinks;
    }

    private String removeEscape(String match) {
        if (match != null) {
            return match.replaceAll("\\\\", "");
        }
        return null;
    }

    private String getRelPath(MegaFolder folder, HashMap<String, MegaFolder> folders) {
        if (folder == null) {
            return "/";
        }
        StringBuilder ret = new StringBuilder();
        while (true) {

            ret.insert(0, folder.name);
            ret.insert(0, "/");
            MegaFolder parent = folders.get(folder.parent);
            if (parent == null || parent == folder) {
                //
                return ret.toString();
            }
            folder = parent;
        }

    }

    private String decryptNodeKey(String encryptedNodeKey, String masterKey) throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {
        byte[] masterKeyBytes = b64decode(masterKey);
        byte[] encryptedNodeKeyBytes = b64decode(encryptedNodeKey);
        byte[] ret = new byte[encryptedNodeKeyBytes.length];
        byte[] iv = aInt_to_aByte(0, 0, 0, 0);
        for (int index = 0; index < ret.length; index = index + 16) {
            final IvParameterSpec ivSpec = new IvParameterSpec(iv);
            final SecretKeySpec skeySpec = new SecretKeySpec(masterKeyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/CBC/nopadding");
            cipher.init(Cipher.DECRYPT_MODE, skeySpec, ivSpec);
            System.arraycopy(cipher.doFinal(Arrays.copyOfRange(encryptedNodeKeyBytes, index, index + 16)), 0, ret, index, 16);
        }
        return Base64.encodeToString(ret, false);

    }

    private String getField(String field, String input) {
        return new Regex(input, "\"" + field + "\":\\s*?\"?(.*?)(\"|,)").getMatch(0);
    }

    private byte[] b64decode(String data) {
        data = data.replace(",", "");
        data += "==".substring((2 - data.length() * 3) & 3);
        data = data.replace("-", "+").replace("_", "/");
        return Base64.decode(data);
    }

    private byte[] aInt_to_aByte(int... intKey) {
        byte[] buffer = new byte[intKey.length * 4];
        ByteBuffer bb = ByteBuffer.wrap(buffer);
        for (int i = 0; i < intKey.length; i++) {
            bb.putInt(intKey[i]);
        }
        return bb.array();
    }

    private int[] aByte_to_aInt(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        int[] res = new int[bytes.length / 4];
        for (int i = 0; i < res.length; i++) {
            res[i] = bb.getInt(i * 4);
        }
        return res;
    }

    private String decrypt(String input, String keyString) throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, UnsupportedEncodingException, PluginException {
        byte[] b64Dec = b64decode(keyString);
        int[] intKey = aByte_to_aInt(b64Dec);
        byte[] key = null;
        if (intKey.length == 4) {
            /* folder key */
            key = b64Dec;
        } else {
            /* file key */
            key = aInt_to_aByte(intKey[0] ^ intKey[4], intKey[1] ^ intKey[5], intKey[2] ^ intKey[6], intKey[3] ^ intKey[7]);
        }
        byte[] iv = aInt_to_aByte(0, 0, 0, 0);
        final IvParameterSpec ivSpec = new IvParameterSpec(iv);
        final SecretKeySpec skeySpec = new SecretKeySpec(key, "AES");
        Cipher cipher = Cipher.getInstance("AES/CBC/nopadding");
        cipher.init(Cipher.DECRYPT_MODE, skeySpec, ivSpec);
        byte[] unPadded = b64decode(input);
        int len = 16 - ((unPadded.length - 1) & 15) - 1;
        byte[] payLoadBytes = new byte[unPadded.length + len];
        System.arraycopy(unPadded, 0, payLoadBytes, 0, unPadded.length);
        payLoadBytes = cipher.doFinal(payLoadBytes);
        String ret = new String(payLoadBytes, "UTF-8");
        if (ret != null && !ret.startsWith("MEGA{")) {
            /* verify if the keyString is correct */
            return null;
        }
        return ret;
    }

    private String getFolderID(CryptedLink link) {
        return new Regex(link.getCryptedUrl(), "#F\\!([a-zA-Z0-9]+)\\!").getMatch(0);
    }

    private String getMasterKey(CryptedLink link) {
        return new Regex(link.getCryptedUrl(), "#F\\![a-zA-Z0-9]+\\!([a-zA-Z0-9_,\\-]+)").getMatch(0);
    }

}
