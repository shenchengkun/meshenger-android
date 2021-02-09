/*
 * Generate possible IP addresses  to reach a contact.
*/
package d.d.meshenger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class AddressUtils {
    private static final String TAG = "AddressUtils";

    public static InetSocketAddress[] getAllSocketAddresses(List<String> initial_addresses, InetSocketAddress last_working_address, int port) {
        Set<InetSocketAddress> address_set = new HashSet<>();

        if (last_working_address != null) {
            address_set.add(last_working_address);
        }


        for (String address : initial_addresses) {
            try {
                if (Utils.isMAC(address)) {
                    // use own addresses as template
                    address_set.addAll(getAddressPermutations(address, port));
                    // from neighbor table
                    address_set.addAll(getAddressesFromNeighborTable(address, port));
                } else {
                    // parse address
                    address_set.add(InetSocketAddress.createUnresolved(address, port));
                }
            } catch (Exception e) {
                Log.e(TAG, "invalid address: " + address);
                e.printStackTrace();
            }
        }

        // sort addresses, prefer last successful address and IPv6
        InetSocketAddress[] address_array = address_set.toArray(new InetSocketAddress[0]);
        Arrays.sort(address_array, new Comparator<InetSocketAddress>() {
            private int addressValue(InetAddress addr) {
                if (last_working_address != null && last_working_address.getAddress() == addr) {
                    return 100;
                }
                if (addr instanceof Inet6Address) {
                    Inet6Address addr6 = (Inet6Address) addr;
                    if (addr6.isAnyLocalAddress()) {
                        return 50;
                    }
                    return 30;
                }
                if (addr instanceof Inet4Address) {
                    // Inet4Address addr4 = (Inet4Address) addr;
                    return 20;
                }
                return 0;
            }
            @Override
            public int compare(InetSocketAddress lhs, InetSocketAddress rhs) {
                // -1 - less than, 1 - greater than, 0 - equal, all inversed for descending
                return addressValue(lhs.getAddress()) - addressValue(rhs.getAddress());
            }
        });

        for (InetSocketAddress address : address_array) {
            Log.d(TAG, "got address: " + address);
        }

        return address_array;
    }

    public static List<AddressEntry> getOwnAddresses() {
        ArrayList<AddressEntry> addressList = new ArrayList<>();
        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : all) {
                byte[] mac = nif.getHardwareAddress();

                if (nif.isLoopback()) {
                    continue;
                }

                if (Utils.isMAC(mac)) {
                    addressList.add(new AddressEntry(Utils.bytesToMacAddress(mac), nif.getName(), Utils.isMulticastMAC(mac)));
                }

                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr.isLoopbackAddress()) {
                        continue;
                    }

                    addressList.add(new AddressEntry(addr.getHostAddress(), nif.getName(), addr.isMulticastAddress()));
                }
            }
        } catch (Exception ex) {
            // ignore
            Log.d(TAG, "error: " + ex.toString());
        }

        return addressList;
    }

    // list all IP/MAC addresses of running network interfaces
    // for debugging only
    public static void printOwnAddresses() {
        for (AddressEntry ae : getOwnAddresses()) {
            Log.d(TAG, "Address: " + ae.address + " (" + ae.device + (ae.multicast ? ", multicast" : "") + ")");
        }
    }

    // Check if the given MAC address is in the IPv6 address
    static byte[] getEUI64MAC(Inet6Address addr6) {
        byte[] bytes = addr6.getAddress();
        if (bytes[11] != ((byte) 0xFF) || bytes[12] != ((byte) 0xFE)) {
            return null;
        }

        byte[] mac = new byte[6];
        mac[0] = (byte) (bytes[8] ^ 2);
        mac[1] = bytes[9];
        mac[2] = bytes[10];
        mac[3] = bytes[13];
        mac[4] = bytes[14];
        mac[5] = bytes[15];
        return mac;
    }

    private static List<InetSocketAddress> getAddressesFromNeighborTable(String lookup_mac, int port) {
        List<InetSocketAddress> addrs = new ArrayList<>();
        try {
        	// get IPv4 and IPv6 entries
            Process pc = Runtime.getRuntime().exec("ip n l");
            BufferedReader rd = new BufferedReader(
                new InputStreamReader(pc.getInputStream(), "UTF-8")
            );

            String line = "";
            while ((line = rd.readLine()) != null) {
                String[] tokens = line.split("\\s+");
                // IPv4
                if (tokens.length == 6) {
                    String addr = tokens[0];
                    String mac = tokens[4];
                    String device = tokens[2];
                    if (mac.equalsIgnoreCase(lookup_mac) && Utils.isIP(addr)) {
                        if (addr.startsWith("fe80:") || addr.startsWith("169.254.")) {
                            addrs.add(new InetSocketAddress(addr + "%" + device, port));
                        } else {
                            addrs.add(new InetSocketAddress(addr, port));
                        }
                    }
                }

                // IPv6
                if (tokens.length == 7) {
                    String addr = tokens[0];
                    String device = tokens[2];
                    String mac = tokens[4];
                    if (mac.equalsIgnoreCase(lookup_mac) && Utils.isIP(addr)) {
                        if (addr.startsWith("fe80:") || addr.startsWith("169.254.")) {
                            addrs.add(new InetSocketAddress(addr + "%" + device, port));
                        } else {
                            addrs.add(new InetSocketAddress(addr, port));
                        }
                    }
                }
            }
        } catch (IOException e) {
            Log.d(TAG, e.toString());
        }

        return addrs;
    }

    /*
    * Replace the MAC address of an EUi64 scheme IPv6 address with another MAC address.
    * E.g.: ("fe80::aaaa:aaff:faa:aaa", "bb:bb:bb:bb:bb:bb") => "fe80::9bbb:bbff:febb:bbbb"
    */
    private static Inet6Address createEUI64Address(Inet6Address addr6, byte[] mac) {
        // addr6 is expected to be a EUI64 address
        try {
            byte[] bytes = addr6.getAddress();

            bytes[8] = (byte) (mac[0] ^ 2);
            bytes[9] = mac[1];
            bytes[10] = mac[2];

            // already set, but doesn't harm
            bytes[11] = (byte) 0xFF;
            bytes[12] = (byte) 0xFE;

            bytes[13] = mac[3];
            bytes[14] = mac[4];
            bytes[15] = mac[5];

            return Inet6Address.getByAddress(null, bytes, addr6.getScopeId());
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /*
    * Iterate all own addresses of the device. Check if they conform to the EUI64 scheme.
    * If yes, replace the MAC address in it with the supplied one and return that address.
    * Also set the given port for those generated addresses.
    */
    private static List<InetSocketAddress> getAddressPermutations(String contact_mac, int port) {
        byte[] contact_mac_bytes = Utils.macAddressToBytes(contact_mac);
        ArrayList<InetSocketAddress> addrs = new ArrayList<InetSocketAddress>();
        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : all) {
                if (nif.isLoopback() || nif.getName().equals("dummy0")) {
                    continue;
                }

                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr.isLoopbackAddress()) {
                        continue;
                    }

                    if (addr instanceof Inet6Address) {
                        Inet6Address addr6 = (Inet6Address) addr;
                        byte[] extracted_mac = getEUI64MAC(addr6);
                        if (extracted_mac != null && Arrays.equals(extracted_mac, nif.getHardwareAddress())) {
                            // We found the interface MAC address in the IPv6 address assigned to that interface in the EUI-64 scheme.
                            // Now assume that the contact has an address with the same scheme.
                            InetAddress new_addr = createEUI64Address(addr6, contact_mac_bytes);
                            if (new_addr != null) {
                                addrs.add(new InetSocketAddress(new_addr, port));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return addrs;
    }
}
