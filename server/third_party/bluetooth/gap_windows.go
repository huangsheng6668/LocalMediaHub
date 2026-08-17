package bluetooth

import (
	"context"
	"errors"
	"fmt"
	"syscall"
	"unsafe"

	"github.com/go-ole/go-ole"
	"github.com/saltosystems/winrt-go"
	"github.com/saltosystems/winrt-go/windows/devices/bluetooth"
	"github.com/saltosystems/winrt-go/windows/devices/bluetooth/advertisement"
	"github.com/saltosystems/winrt-go/windows/devices/bluetooth/genericattributeprofile"
	"github.com/saltosystems/winrt-go/windows/foundation"
	"github.com/saltosystems/winrt-go/windows/foundation/collections"
	"github.com/saltosystems/winrt-go/windows/storage/streams"
)

// Address contains a Bluetooth MAC address.
type Address struct {
	MACAddress
}

type Advertisement struct {
	advertisement *advertisement.BluetoothLEAdvertisement
	publisher     *advertisement.BluetoothLEAdvertisementPublisher
}

// DefaultAdvertisement returns the default advertisement instance but does not
// configure it.
func (a *Adapter) DefaultAdvertisement() *Advertisement {
	if a.defaultAdvertisement == nil {
		a.defaultAdvertisement = &Advertisement{}
	}

	return a.defaultAdvertisement
}

// Configure this advertisement.
// on Windows we're only able to set "Manufacturer Data" for advertisements.
// https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.advertisement.bluetoothleadvertisementpublisher?view=winrt-22621#remarks
// following this c# source for this implementation: https://github.com/microsoft/Windows-universal-samples/blob/main/Samples/BluetoothAdvertisement/cs/Scenario2_Publisher.xaml.cs
// adding service data / localname leads to errors when starting the advertisement.
func (a *Advertisement) Configure(options AdvertisementOptions) error {
	// we can only advertise manufacturer / company data on windows, so no need to continue if we have none
	if len(options.ManufacturerData) == 0 {
		return nil
	}

	if a.publisher != nil {
		a.publisher.Release()
	}

	if a.advertisement != nil {
		a.advertisement.Release()
	}

	pub, err := advertisement.NewBluetoothLEAdvertisementPublisher()
	if err != nil {
		return err
	}

	a.publisher = pub

	ad, err := a.publisher.GetAdvertisement()
	if err != nil {
		return err
	}

	a.advertisement = ad

	vec, err := ad.GetManufacturerData()
	if err != nil {
		return err
	}

	for _, optManData := range options.ManufacturerData {
		writer, err := streams.NewDataWriter()
		if err != nil {
			return err
		}
		defer writer.Release()

		err = writer.WriteBytes(uint32(len(optManData.Data)), optManData.Data)
		if err != nil {
			return err
		}

		buf, err := writer.DetachBuffer()
		if err != nil {
			return err
		}

		manData, err := advertisement.BluetoothLEManufacturerDataCreate(optManData.CompanyID, buf)
		if err != nil {
			return err
		}

		if err = vec.Append(unsafe.Pointer(&manData.IUnknown.RawVTable)); err != nil {
			return err
		}
	}

	return nil
}

// Start advertisement. May only be called after it has been configured.
func (a *Advertisement) Start() error {
	// publisher will be present if we actually have manufacturer data to advertise.
	if a.publisher != nil {
		return a.publisher.Start()
	}

	return nil
}

// Stop advertisement. May only be called after it has been started.
func (a *Advertisement) Stop() error {
	if a.publisher != nil {
		return a.publisher.Stop()
	}

	return nil
}

// Scan starts a BLE scan. It is stopped by a call to StopScan. A common pattern
// is to cancel the scan when a particular device has been found.
func (a *Adapter) Scan(callback func(*Adapter, ScanResult)) (err error) {
	if a.watcher != nil {
		// Cannot scan more than once: which one should ScanStop()
		// stop?
		return errScanning
	}

	a.watcher, err = advertisement.NewBluetoothLEAdvertisementWatcher()
	if err != nil {
		return
	}
	defer func() {
		_ = a.watcher.Release()
		a.watcher = nil
	}()

	// Set scanning mode to active so we receive scan responses
	// from devices in advertising mode
	err = a.watcher.SetScanningMode(advertisement.BluetoothLEScanningModeActive)
	if err != nil {
		return
	}

	// Listen for incoming BLE advertisement packets.
	// We need a TypedEventHandler<TSender, TResult> to listen to events, but since this is a parameterized delegate
	// its GUID depends on the classes used as sender and result, so we need to compute it:
	// TypedEventHandler<BluetoothLEAdvertisementWatcher, BluetoothLEAdvertisementReceivedEventArgs>
	eventReceivedGuid := winrt.ParameterizedInstanceGUID(
		foundation.GUIDTypedEventHandler,
		advertisement.SignatureBluetoothLEAdvertisementWatcher,
		advertisement.SignatureBluetoothLEAdvertisementReceivedEventArgs,
	)
	handler := foundation.NewTypedEventHandler(ole.NewGUID(eventReceivedGuid), func(instance *foundation.TypedEventHandler, sender, arg unsafe.Pointer) {
		args := (*advertisement.BluetoothLEAdvertisementReceivedEventArgs)(arg)
		result := getScanResultFromArgs(args)
		callback(a, result)
	})
	defer handler.Release()

	token, err := a.watcher.AddReceived(handler)
	if err != nil {
		return
	}
	defer a.watcher.RemoveReceived(token)

	// Wait for when advertisement has stopped by a call to StopScan().
	// Advertisement doesn't seem to stop right away, there is an
	// intermediate Stopping state.
	stoppingChan := make(chan error)
	// TypedEventHandler<BluetoothLEAdvertisementWatcher, BluetoothLEAdvertisementWatcherStoppedEventArgs>
	eventStoppedGuid := winrt.ParameterizedInstanceGUID(
		foundation.GUIDTypedEventHandler,
		advertisement.SignatureBluetoothLEAdvertisementWatcher,
		advertisement.SignatureBluetoothLEAdvertisementWatcherStoppedEventArgs,
	)
	stoppedHandler := foundation.NewTypedEventHandler(ole.NewGUID(eventStoppedGuid), func(_ *foundation.TypedEventHandler, _, arg unsafe.Pointer) {
		args := (*advertisement.BluetoothLEAdvertisementWatcherStoppedEventArgs)(arg)
		errCode, err := args.GetError()
		if err != nil {
			// Got an error while getting the error value, that shouldn't
			// happen.
			stoppingChan <- fmt.Errorf("failed to get stopping error value: %w", err)
		} else if errCode != bluetooth.BluetoothErrorSuccess {
			// Could not stop the scan? I'm not sure when this would actually
			// happen.
			stoppingChan <- fmt.Errorf("failed to stop scanning (error code %d)", errCode)
		}
		close(stoppingChan)
	})
	defer stoppedHandler.Release()

	token, err = a.watcher.AddStopped(stoppedHandler)
	if err != nil {
		return
	}
	defer a.watcher.RemoveStopped(token)

	err = a.watcher.Start()
	if err != nil {
		return err
	}

	// Wait until advertisement has stopped, and finish.
	return <-stoppingChan
}

func getScanResultFromArgs(args *advertisement.BluetoothLEAdvertisementReceivedEventArgs) ScanResult {
	// parse bluetooth address
	addr, _ := args.GetBluetoothAddress()
	adr := Address{}
	for i := range adr.MAC {
		adr.MAC[i] = byte(addr)
		addr >>= 8
	}
	sigStrength, _ := args.GetRawSignalStrengthInDBm()
	result := ScanResult{
		RSSI:    sigStrength,
		Address: adr,
	}

	winAdv, err := args.GetAdvertisement()
	if err != nil {
		return result
	}
	defer winAdv.Release()

	var manufacturerData []ManufacturerDataElement
	var serviceUUIDs []UUID
	if winAdv, err := args.GetAdvertisement(); err == nil && winAdv != nil {
		// Extract manufacturer data
		vector, _ := winAdv.GetManufacturerData()
		size, _ := vector.GetSize()
		for i := uint32(0); i < size; i++ {
			element, _ := vector.GetAt(i)
			manData := (*advertisement.BluetoothLEManufacturerData)(element)

			companyID, _ := manData.GetCompanyId()
			buffer, _ := manData.GetData()
			manufacturerData = append(manufacturerData, ManufacturerDataElement{
				CompanyID: companyID,
				Data:      bufferToSlice(buffer),
			})
		}

		// Extract service UUIDs
		vector, _ = winAdv.GetServiceUuids()
		size, _ = vector.GetSize()
		for i := uint32(0); i < size; i++ {
			guid, err := vectorGetGuidAt(vector, i)
			if err != nil {
				continue
			}
			serviceUUIDs = append(serviceUUIDs, GUIDToUUID(guid))
		}
	}

	// Note: the IsRandom bit is never set.
	localName, _ := winAdv.GetLocalName()
	result.AdvertisementPayload = &advertisementFields{
		AdvertisementFields{
			LocalName:        localName,
			ServiceUUIDs:     serviceUUIDs,
			ManufacturerData: manufacturerData,
		},
	}

	return result
}

// vectorGetGuidAt reads a Guid element from an IVector by invoking the COM
// vtable directly with a full 16-byte destination buffer.
//
// winrt-go's generic IVector.GetAt marshals elements through an 8-byte
// unsafe.Pointer out-slot. For a 16-byte GUID element the marshaller writes
// the full struct starting at that slot, so the upper 8 bytes land past it in
// GetAt's own frame and are destroyed when the function returns — every
// extracted UUID came back with garbage in its last 8 bytes (16-bit service
// UUIDs masked the bug because their base-UUID tail is constant; 128-bit
// UUIDs never matched). Calling the vtable ourselves with a syscall.GUID
// buffer avoids the truncation entirely.
func vectorGetGuidAt(v *collections.IVector, index uint32) (syscall.GUID, error) {
	var guid syscall.GUID
	hr, _, _ := syscall.SyscallN(
		v.VTable().GetAt,
		uintptr(unsafe.Pointer(v)),     // this
		uintptr(index),                 // in uint32
		uintptr(unsafe.Pointer(&guid)), // out GUID (16 bytes, full width)
	)
	if hr != 0 {
		return syscall.GUID{}, ole.NewError(hr)
	}
	return guid, nil
}

func GUIDToUUID(guid syscall.GUID) UUID {
	return NewUUID([16]byte{
		byte(guid.Data1 >> 24),
		byte(guid.Data1 >> 16),
		byte(guid.Data1 >> 8),
		byte(guid.Data1),
		byte(guid.Data2 >> 8),
		byte(guid.Data2),
		byte(guid.Data3 >> 8),
		byte(guid.Data3),
		guid.Data4[0], guid.Data4[1],
		guid.Data4[2], guid.Data4[3],
		guid.Data4[4], guid.Data4[5],
		guid.Data4[6], guid.Data4[7],
	})
}

func bufferToSlice(buffer *streams.IBuffer) []byte {
	dataReader, _ := streams.DataReaderFromBuffer(buffer)
	defer dataReader.Release()
	bufferSize, _ := buffer.GetLength()
	if bufferSize == 0 {
		return nil
	}
	data, _ := dataReader.ReadBytes(bufferSize)
	return data
}

// StopScan stops any in-progress scan. It can be called from within a Scan
// callback to stop the current scan. If no scan is in progress, an error will
// be returned.
func (a *Adapter) StopScan() error {
	if a.watcher == nil {
		return errNotScanning
	}
	return a.watcher.Stop()
}

var _ GAPDevice = Device{}

// Device is a connection to a remote peripheral.
type Device struct {
	ctx    context.Context
	cancel context.CancelFunc

	Address Address // the MAC address of the device

	device                        *bluetooth.BluetoothLEDevice
	session                       *genericattributeprofile.GattSession
	connectionStatusListenerToken foundation.EventRegistrationToken
	connectionStatusListener      *foundation.TypedEventHandler
}

// Connect starts a connection attempt to the given peripheral device address.
//
// On Linux and Windows, the IsRandom part of the address is ignored.
func (a *Adapter) Connect(address Address, params ConnectionParams) (Device, error) {
	var winAddr uint64
	for i := range address.MAC {
		winAddr += uint64(address.MAC[i]) << (8 * i)
	}

	// IAsyncOperation<BluetoothLEDevice>
	bleDeviceOp, err := bluetooth.BluetoothLEDeviceFromBluetoothAddressAsync(winAddr)
	if err != nil {
		return Device{}, err
	}

	// We need to pass the signature of the parameter returned by the async operation:
	// IAsyncOperation<BluetoothLEDevice>
	if err := awaitAsyncOperation(bleDeviceOp, bluetooth.SignatureBluetoothLEDevice); err != nil {
		return Device{}, fmt.Errorf("error connecting to device: %w", err)
	}

	res, err := bleDeviceOp.GetResults()
	if err != nil {
		return Device{}, err
	}

	// The returned BluetoothLEDevice is set to null if FromBluetoothAddressAsync can't find the device identified by bluetoothAddress
	if uintptr(res) == 0x0 {
		return Device{}, fmt.Errorf("device with the given address was not found")
	}

	bleDevice := (*bluetooth.BluetoothLEDevice)(res)

	// Creating a BluetoothLEDevice object by calling this method alone doesn't (necessarily) initiate a connection.
	// To initiate a connection, we need to set GattSession.MaintainConnection to true.
	dID, err := bleDevice.GetBluetoothDeviceId()
	if err != nil {
		return Device{}, err
	}

	// Windows does not support explicitly connecting to a device.
	// Instead it has the concept of a GATT session that is owned
	// by the calling program.
	gattSessionOp, err := genericattributeprofile.GattSessionFromDeviceIdAsync(dID) // IAsyncOperation<GattSession>
	if err != nil {
		return Device{}, err
	}

	if err := awaitAsyncOperation(gattSessionOp, genericattributeprofile.SignatureGattSession); err != nil {
		return Device{}, fmt.Errorf("error getting gatt session: %w", err)
	}

	gattRes, err := gattSessionOp.GetResults()
	if err != nil {
		return Device{}, err
	}
	newSession := (*genericattributeprofile.GattSession)(gattRes)
	// LocalMediaHub patch: do NOT call SetMaintainConnection(true). Upstream
	// pins the GATT session open so the link survives brief peer drops — but
	// that is exactly what leaves a residual WinRT-side session after the
	// Android app is killed. The residual blocks every subsequent
	// DiscoverServices ("operation failed with code 1/2") and cannot be cleared
	// safely: any GattSession method (Close, SetMaintainConnection(false))
	// routes through go-ole queryInterface on a dangling COM pointer and
	// faults the process. With maintain_connection left at its default
	// (false), WinRT releases the session when the peer drops, so the next
	// Connect starts clean. We never relied on auto-reconnect anyway — the
	// application explicitly reconnects via Connect().

	ctx, cancel := context.WithCancel(context.Background())

	device := Device{
		ctx:    ctx,
		cancel: cancel,

		Address: address,

		device:  bleDevice,
		session: newSession,
	}

	// https://learn.microsoft.com/es-es/uwp/api/windows.devices.bluetooth.bluetoothledevice.connectionstatuschanged?view=winrt-26100
	// TypedEventHandler<BluetoothLEDevice,object>
	connectionStatusChangedGUID := winrt.ParameterizedInstanceGUID(
		foundation.GUIDTypedEventHandler,
		bluetooth.SignatureBluetoothLEDevice,
		"cinterface(IInspectable)", // object
	)

	handler := foundation.NewTypedEventHandler(ole.NewGUID(connectionStatusChangedGUID), func(instance *foundation.TypedEventHandler, sender, arg unsafe.Pointer) {
		status, err := bleDevice.GetConnectionStatus()
		if err != nil {
			return
		}
		// LocalMediaHub patch: the upstream auto-Disconnect-on-peer-drop crashes
		// the process. When the remote peer (Android app) tears the GATT link
		// down, both BluetoothLEDevice and GattSession COM pointers are left
		// dangling, so device.Disconnect() → GattSession.Close → go-ole
		// queryInterface dereferences freed memory → signal 0xc0000005 fatal
		// fault (unrecoverable). The connectHandler below still notifies
		// adapter-level listeners of the disconnect; cleanup of the Go-side
		// device reference is left to the application (which drops the
		// reference and lets finalizers reclaim the COM objects).
		_ = status

		if a.connectHandler != nil {
			a.connectHandler(device, status == bluetooth.BluetoothConnectionStatusConnected)
		}
	})

	token, err := device.device.AddConnectionStatusChanged(handler)

	device.connectionStatusListenerToken = token
	device.connectionStatusListener = handler

	if err != nil {
		_ = handler.Release()
		return device, err
	}

	return device, nil
}

// Disconnect from the BLE device. This method is non-blocking and does not
// wait until the connection is fully gone.
func (d Device) Disconnect() error {
	defer d.device.Release()
	defer d.session.Release()
	if d.connectionStatusListener != nil {
		defer d.connectionStatusListener.Release()
	}

	d.cancel()

	if err := d.session.Close(); err != nil {
		return err
	}

	_ = d.device.RemoveConnectionStatusChanged(d.connectionStatusListenerToken)

	if err := d.device.Close(); err != nil {
		return err
	}

	return nil
}

// Connected returns whether the device is currently connected.
func (d Device) Connected() (bool, error) {
	if d.device == nil {
		return false, nil
	}
	status, err := d.device.GetConnectionStatus()
	if err != nil {
		return false, err
	}
	return status == bluetooth.BluetoothConnectionStatusConnected, nil
}

// RequestConnectionParams requests a different connection latency and timeout
// of the given device connection. Fields that are unset will be left alone.
// Whether or not the device will actually honor this, depends on the device and
// on the specific parameters.
//
// On Windows, this call doesn't do anything.
func (d Device) RequestConnectionParams(params ConnectionParams) error {
	// TODO: implement this using
	// BluetoothLEDevice.RequestPreferredConnectionParameters.
	return nil
}

// SetRandomAddress sets the random address to be used for advertising.
func (a *Adapter) SetRandomAddress(mac MAC) error {
	return errors.ErrUnsupported
}
